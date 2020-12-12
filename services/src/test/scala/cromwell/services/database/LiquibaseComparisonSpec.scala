package cromwell.services.database

import com.dimafeng.testcontainers.Container
import common.assertion.CromwellTimeoutSpec
import cromwell.core.Tags._
import cromwell.database.slick.SlickDatabase
import cromwell.services.database.LiquibaseComparisonSpec._
import cromwell.services.database.LiquibaseOrdering._
import liquibase.snapshot.DatabaseSnapshot
import liquibase.statement.DatabaseFunction
import liquibase.structure.DatabaseObject
import liquibase.structure.core._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import slick.jdbc.GetResult

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect._

/**
  * Compares all of the various liquibase schemas against an in-memory HSQLDB-Slick schema.
  */
class LiquibaseComparisonSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers with ScalaFutures {

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = scaled(5.seconds), interval = scaled(100.millis))

  CromwellDatabaseType.All foreach { databaseType =>

    lazy val expectedSnapshot = DatabaseTestKit.inMemorySnapshot(databaseType, SlickSchemaManager)
    lazy val expectedColumns = get[Column](expectedSnapshot).sorted
    lazy val expectedPrimaryKeys = get[PrimaryKey](expectedSnapshot).sorted
    lazy val expectedForeignKeys = get[ForeignKey](expectedSnapshot).sorted
    lazy val expectedUniqueConstraints = get[UniqueConstraint](expectedSnapshot).sorted
    lazy val expectedIndexes = get[Index](expectedSnapshot) filterNot DatabaseTestKit.isGenerated

    DatabaseSystem.All foreach { databaseSystem =>

      behavior of s"Liquibase Comparison for ${databaseType.name} ${databaseSystem.name}"

      val containerOpt: Option[Container] = DatabaseTestKit.getDatabaseTestContainer(databaseSystem)

      lazy val liquibasedDatabase = DatabaseTestKit.initializeDatabaseByContainerOptTypeAndSystem(containerOpt, databaseType, databaseSystem)

      lazy val connectionMetadata = DatabaseTestKit.connectionMetadata(liquibasedDatabase)

      lazy val actualSnapshot = DatabaseTestKit.liquibaseSnapshot(liquibasedDatabase)
      lazy val actualColumns = get[Column](actualSnapshot)
      lazy val actualPrimaryKeys = get[PrimaryKey](actualSnapshot)
      lazy val actualForeignKeys = get[ForeignKey](actualSnapshot)
      lazy val actualUniqueConstraints = get[UniqueConstraint](actualSnapshot)
      lazy val actualIndexes = get[Index](actualSnapshot)

      /* SQLite's doesn't return unique constraints via JDBC metadata, so we will do it here */
      lazy val databaseUniqueConstraints = {
        val dbio = uniqueConstraintDbio(databaseSystem, liquibasedDatabase)
        val future = liquibasedDatabase.database.run(dbio)
        future.futureValue
      }

      /*
      SQLite can't return FK names when quoting objects for PostgreSQL. The regex used to generate JDBC metadata does
      not match because of quotes:
      https://github.com/xerial/sqlite-jdbc/blob/3.32.3.2/src/main/java/org/sqlite/jdbc3/JDBC3DatabaseMetaData.java#L2070

      When that regex fails, SQLite gives up and says the FK name is "".

      Liquibase then builds a Map of FKs... with the key to the map being the name. Thus only one FK ends up in the map
      since all SQLite FKs have the same empty-string name.

      Instead call a custom FK generator.
       */
      lazy val databaseForeignKeys = {
        val dbio = foreignKeyDbio(databaseSystem, liquibasedDatabase)
        val future = liquibasedDatabase.database.run(dbio)
        future.futureValue
      }

      lazy val columnMapping = getColumnMapping(databaseSystem)

      it should "start container if required" taggedAs DbmsTest in {
        containerOpt.foreach { _.start }
      }

      expectedColumns foreach { expectedColumn =>
        val description = s"column ${expectedColumn.getRelation.getName}.${expectedColumn.getName}"

        it should s"match the Slick schema for $description" taggedAs DbmsTest in {
          val actualColumnOption = actualColumns find { actualColumn =>
            ColumnDescription.from(actualColumn) == ColumnDescription.from(expectedColumn)
          }
          val actualColumn = actualColumnOption getOrElse fail(s"Did not find $description")

          withClue(s"for type " +
            s"${actualColumn.getType.getTypeName}(default = ${actualColumn.getDefaultValue}) vs. " +
            s"${expectedColumn.getType.getTypeName}(default = ${expectedColumn.getDefaultValue}):") {

            val actualColumnType = ColumnType.from(actualColumn)

            actualColumn.isAutoIncrement should be(expectedColumn.isAutoIncrement)
            if (expectedColumn.isAutoIncrement) {
              // Auto increment columns may have different types, such as SERIAL/BIGSERIAL
              // https://www.postgresql.org/docs/11/datatype-numeric.html#DATATYPE-SERIAL
              val actualColumnDefault = ColumnDefault(actualColumnType, actualColumn.getDefaultValue)
              val autoIncrementDefault =
                getAutoIncrementDefault(expectedColumn, columnMapping, databaseSystem, connectionMetadata)
              actualColumnDefault should be(autoIncrementDefault)
            } else {

              // Check the column type
              val mappedColumnType = getColumnType(expectedColumn, columnMapping)
              actualColumnType should be(mappedColumnType)

              if (isSlickDefaultNull(expectedColumn)) {
                // The column has a default value of "null"
                // There are a number of ways off expressing null/None/NULL when dealing with CLOB/BLOB/BIT etc.
                val expectedOptions = Set(
                  None,
                  Option(DefaultNullBoolean),
                  Option(DefaultNullString),
                  Option(DefaultNullFunction),
                )
                List(Option(actualColumn.getDefaultValue)) should contain atLeastOneElementOf expectedOptions
              } else {

                // Check the default value
                val mappedDefaultValue = getColumnDefault(expectedColumn, columnMapping)
                actualColumn.getDefaultValue should be(mappedDefaultValue)
              }
            }

            // Check for column nullability
            val nullTodos = getNullTodos(databaseSystem, databaseType)
            if (nullTodos.contains(ColumnDescription.from(actualColumn))) {
              // Oops. This column is nullable. TODO: make a changelog to fix, and then remove it from the list
              assert(actualColumn.isNullable, "Column is explicitly listed as a null field:")
            } else {
              actualColumn.isNullable should be(expectedColumn.isNullable)
            }

            // Some types aren't available via liquibase, so go into the database to get the types
            columnTypeValidationOption(expectedColumn, databaseSystem) foreach { expectedColumnType =>
              val dbio = columnTypeDbio(expectedColumn, databaseSystem, liquibasedDatabase)
              val future = liquibasedDatabase.database.run(dbio)
              future.futureValue should be(expectedColumnType)
            }

            // Verify that sequence widths are the same as columns
            val expectedSequenceTypeOption = sequenceTypeValidationOption(
              expectedColumn,
              databaseSystem,
              connectionMetadata,
            )
            expectedSequenceTypeOption foreach { expectedSequenceType =>
              val dbio = sequenceTypeDbio(expectedColumn, databaseSystem, liquibasedDatabase)
              val future = liquibasedDatabase.database.run(dbio)
              val res = future.futureValue
              res should be(expectedSequenceType)
            }

          }
        }
      }

      expectedPrimaryKeys foreach { expectedPrimaryKey =>
        val description = s"primary key on ${expectedPrimaryKey.getTable.getName}"

        it should s"match the Slick schema for $description" taggedAs DbmsTest in {
          val actualPrimaryKeyOption = actualPrimaryKeys find {
            _.getTable.getName == expectedPrimaryKey.getTable.getName
          }
          val actualPrimaryKey = actualPrimaryKeyOption getOrElse fail(s"Did not find $description")

          actualPrimaryKey.getColumns.asScala.map(ColumnDescription.from) should
            contain theSameElementsAs expectedPrimaryKey.getColumns.asScala.map(ColumnDescription.from)
        }
      }

      expectedForeignKeys foreach { expectedForeignKey =>
        val description = s"foreign key ${expectedForeignKey.getName}"

        it should s"match the Slick schema for $description" taggedAs DbmsTest in {

          val actualForeignKeyOption =
            (databaseForeignKeys match {
              case Seq() => actualForeignKeys
              case nonEmpty => nonEmpty
            }).find({
              _.getName == expectedForeignKey.getName
            })

          val actualForeignKey = actualForeignKeyOption getOrElse fail(s"Did not find $description")

          actualForeignKey.getPrimaryKeyTable.getName should be(expectedForeignKey.getPrimaryKeyTable.getName)
          actualForeignKey.getForeignKeyTable.getName should be(expectedForeignKey.getForeignKeyTable.getName)
          actualForeignKey.getPrimaryKeyColumns.asScala.map(ColumnDescription.from) should
            contain theSameElementsAs expectedForeignKey.getPrimaryKeyColumns.asScala.map(ColumnDescription.from)
          actualForeignKey.getForeignKeyColumns.asScala.map(ColumnDescription.from) should
            contain theSameElementsAs expectedForeignKey.getForeignKeyColumns.asScala.map(ColumnDescription.from)

          expectedForeignKey.getUpdateRule match {
            case ForeignKeyConstraintType.importedKeyRestrict | ForeignKeyConstraintType.importedKeyNoAction =>
              actualForeignKey.getUpdateRule should
                (be(ForeignKeyConstraintType.importedKeyRestrict) or be(ForeignKeyConstraintType.importedKeyNoAction))
            case other => actualForeignKey.getUpdateRule should be(other)
          }
          expectedForeignKey.getDeleteRule match {
            case ForeignKeyConstraintType.importedKeyRestrict | ForeignKeyConstraintType.importedKeyNoAction =>
              actualForeignKey.getDeleteRule should
                (be(ForeignKeyConstraintType.importedKeyRestrict) or be(ForeignKeyConstraintType.importedKeyNoAction))
            case other => actualForeignKey.getDeleteRule should be(other)
          }
          actualForeignKey.isDeferrable should be(expectedForeignKey.isDeferrable)
          actualForeignKey.isInitiallyDeferred should be(expectedForeignKey.isInitiallyDeferred)
        }
      }

      expectedUniqueConstraints foreach { expectedUniqueConstraint =>
        val description = s"unique constraint ${expectedUniqueConstraint.getName}"

        it should s"match the Slick schema for $description" taggedAs DbmsTest in {
          val actualUniqueConstraintOption =
            (databaseUniqueConstraints match {
              case Seq() => actualUniqueConstraints
              case nonEmpty => nonEmpty
            }).find({
              _.getName == expectedUniqueConstraint.getName
            })
          val actualUniqueConstraint = actualUniqueConstraintOption getOrElse
            fail(s"Did not find $description")

          actualUniqueConstraint.getRelation.getName should be(expectedUniqueConstraint.getRelation.getName)
          actualUniqueConstraint.getColumns.asScala.map(ColumnDescription.from) should
            contain theSameElementsAs expectedUniqueConstraint.getColumns.asScala.map(ColumnDescription.from)
        }
      }

      expectedIndexes foreach { expectedIndex =>
        val description = s"index ${expectedIndex.getName}"

        it should s"match the Slick schema for $description" taggedAs DbmsTest in {
          val actualIndexOption = actualIndexes find {
            _.getName == expectedIndex.getName
          }
          val actualIndex = actualIndexOption getOrElse fail(s"Did not find $description")

          actualIndex.isUnique should be(expectedIndex.isUnique)
          actualIndex.getColumns.asScala.map(ColumnDescription.from) should
            contain theSameElementsAs expectedIndex.getColumns.asScala.map(ColumnDescription.from)
        }
      }

      it should "close the database" taggedAs DbmsTest in {
        liquibasedDatabase.close()
      }

      it should "stop container if required" taggedAs DbmsTest in {
        containerOpt.foreach { _.stop }
      }
    }
  }
}

object LiquibaseComparisonSpec {
  private def get[T <: DatabaseObject : ClassTag : Ordering](databaseSnapshot: DatabaseSnapshot): Seq[T] = {
    val databaseObjectClass = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    databaseSnapshot.get(databaseObjectClass).asScala.toSeq
  }

  private val DefaultNullBoolean = Boolean.box(false)
  private val DefaultNullString = "NULL"
  private val DefaultNullFunction = new DatabaseFunction(DefaultNullString)

  private def isSlickDefaultNull(column: Column): Boolean = {
    Option(column.getDefaultValue).isEmpty || column.getDefaultValue == DefaultNullFunction
  }

  case class ColumnDescription(tableName: String, columnName: String)

  object ColumnDescription {
    def from(column: Column): ColumnDescription = {
      ColumnDescription(column.getRelation.getName, column.getName)
    }
  }

  case class ColumnType
  (
    typeName: String,
    sizeOption: Option[Int] = None,
  )

  object ColumnType {
    def from(column: Column): ColumnType = {
      ColumnType(
        column.getType.getTypeName.toUpperCase,
        Option(column.getType.getColumnSize).map(_.toInt),
      )
    }
  }

  case class ColumnDefault
  (
    columnType: ColumnType,
    defaultValue: AnyRef,
  )

  object ColumnDefault {
    def from(column: Column): ColumnDefault = {
      ColumnDefault(ColumnType.from(column), column.getDefaultValue)
    }
  }

  case class ColumnMapping
  (
    typeMapping: PartialFunction[ColumnType, ColumnType] = PartialFunction.empty,
    defaultMapping: Map[ColumnDefault, AnyRef] = Map.empty,
  )

  /** Generate the expected PostgreSQL sequence name for a column. */
  private def postgresqlSeqName(column: Column): String = {

    def pad(name: String): String = if (name.endsWith("_")) name else name + "_"

    // Postgres cuts of the length of names around this length
    val Count = 30

    def shorten(name: String, isColumn: Boolean): String = {
      pad {
        // NOTE: Table and column name truncation seems slightly different.
        // This logic was empirically derived. Feel free to modify/simplify!
        if (name.length < Count || isColumn && name.length == Count) {
          name
        } else if (isColumn && name.length == Count + 1) {
          name.dropRight(1)
        } else {
          name.take(Count - 1)
        }
      }
    }

    val tableName = shorten(column.getRelation.getName, isColumn = false)
    val columnName = shorten(column.getName, isColumn = true)
    s"$tableName${columnName}seq"
  }

  // Types as they are represented in HSQLDB that will have different representations in other DBMS.
  private val HsqldbTypeBigInt = ColumnType("BIGINT", Option(64))
  private val HsqldbTypeBlob = ColumnType("BLOB", Option(1073741824))
  private val HsqldbTypeBoolean = ColumnType("BOOLEAN", Option(0))
  private val HsqldbTypeClob = ColumnType("CLOB", Option(1073741824))
  private val HsqldbTypeInteger = ColumnType("INTEGER", Option(32))
  private val HsqldbTypeTimestamp = ColumnType("TIMESTAMP")

  // Defaults as they are represented in HSQLDB that will have different representations in other DBMS.
  private val HsqldbDefaultBooleanTrue = ColumnDefault(HsqldbTypeBoolean, Boolean.box(true))

  // Nothing to map as the original is also HSQLDB
  private val HsqldbColumnMapping = ColumnMapping()

  // Note: BIT vs. TINYINT may be yet another tabs vs. spaces
  // https://stackoverflow.com/questions/11167793/boolean-or-tinyint-confusion/17298805
  private val MysqldbColumnMapping =
    ColumnMapping(
      typeMapping = Map(
        HsqldbTypeBigInt -> ColumnType("BIGINT", Option(19)),
        HsqldbTypeBlob -> ColumnType("LONGBLOB", Option(2147483647)),
        HsqldbTypeBoolean -> ColumnType("TINYINT", Option(3)),
        HsqldbTypeClob -> ColumnType("LONGTEXT", Option(2147483647)),
        HsqldbTypeInteger -> ColumnType("INT", Option(10)),
        HsqldbTypeTimestamp -> ColumnType("DATETIME"),
      ),
      defaultMapping = Map(
        HsqldbDefaultBooleanTrue -> Int.box(1)
      ),
    )

  // MariaDB should behave similar to MySQL except with only large objects having sizes
  private val MariadbColumnMapping =
    ColumnMapping(
      typeMapping = Map(
        HsqldbTypeBigInt -> ColumnType("BIGINT"),
        HsqldbTypeBlob -> ColumnType("LONGBLOB", Option(2147483647)),
        HsqldbTypeBoolean -> ColumnType("TINYINT"),
        HsqldbTypeClob -> ColumnType("LONGTEXT", Option(2147483647)),
        HsqldbTypeInteger -> ColumnType("INT"),
        HsqldbTypeTimestamp -> ColumnType("DATETIME"),
      ),
      defaultMapping = Map(
        HsqldbDefaultBooleanTrue -> Int.box(1),
      ),
    )

  private val PostgresqlColumnMapping =
    ColumnMapping(
      typeMapping = Map(
        HsqldbTypeBigInt -> ColumnType("INT8", None),
        HsqldbTypeBlob -> ColumnType("OID", None),
        HsqldbTypeBoolean -> ColumnType("BOOL", None),
        HsqldbTypeClob -> ColumnType("TEXT", None),
        HsqldbTypeInteger -> ColumnType("INT4", None),
      ),
    )

  // "2000000000" is hardcoded here:
  // https://github.com/xerial/sqlite-jdbc/blob/3.32.3.2/src/main/java/org/sqlite/jdbc3/JDBC3DatabaseMetaData.java#L1189
  private val SQLiteColumnSize = Option(2000000000)
  private val SQLiteTypeBlob = ColumnType("BLOB", SQLiteColumnSize)
  private val SQLiteTypeBoolean = ColumnType("BOOLEAN", SQLiteColumnSize)
  private val SQLiteTypeInteger = ColumnType("INTEGER", SQLiteColumnSize)
  private val SQLiteTypeText = ColumnType("TEXT", SQLiteColumnSize)
  private val SQLiteTypeVarcharConverter: PartialFunction[ColumnType, ColumnType] = {
    // Validate we provided the correct length, even if SQLite knows about the length request but ignores it.
    // https://www.sqlite.org/datatype3.html#affinity_name_examples
    case ColumnType("VARCHAR", Some(size)) => ColumnType(s"VARCHAR($size)", SQLiteColumnSize)
  }
  private val SQLiteColumnMapping =
    ColumnMapping(
      typeMapping =
        SQLiteTypeVarcharConverter orElse
          Map(
            HsqldbTypeBigInt -> SQLiteTypeInteger,
            HsqldbTypeBlob -> SQLiteTypeBlob,
            HsqldbTypeBoolean -> SQLiteTypeBoolean,
            HsqldbTypeClob -> SQLiteTypeText,
            HsqldbTypeInteger -> SQLiteTypeInteger,
            HsqldbTypeTimestamp -> SQLiteTypeText,
          ),
      defaultMapping = Map(
        HsqldbDefaultBooleanTrue -> new DatabaseFunction("1"),
      ),
    )

  /**
    * Returns the column mapping for the DBMS.
    */
  private def getColumnMapping(databaseSystem: DatabaseSystem): ColumnMapping = {
    databaseSystem.platform match {
      case HsqldbDatabasePlatform => HsqldbColumnMapping
      case MariadbDatabasePlatform => MariadbColumnMapping
      case MysqlDatabasePlatform => MysqldbColumnMapping
      case PostgresqlDatabasePlatform => PostgresqlColumnMapping
      case SQLiteDatabasePlatform => SQLiteColumnMapping
    }
  }

  /**
    * Returns the column type, possibly mapped via the ColumnMapping.
    */
  private def getColumnType(column: Column, columnMapping: ColumnMapping): ColumnType = {
    val columnType = ColumnType.from(column)
    columnMapping.typeMapping.applyOrElse[ColumnType, ColumnType](columnType, _ => columnType)
  }

  /**
    * Returns the default for the column, either from ColumnMapping or the column itself.
    */
  private def getColumnDefault(column: Column, columnMapping: ColumnMapping): AnyRef = {
    columnMapping.defaultMapping.getOrElse(ColumnDefault.from(column), column.getDefaultValue)
  }

  /**
    * Return the default for the auto increment column.
    */
  private def getAutoIncrementDefault(column: Column,
                                      columnMapping: ColumnMapping,
                                      databaseSystem: DatabaseSystem,
                                      connectionMetadata: ConnectionMetadata,
                                     ): ColumnDefault = {
    databaseSystem.platform match {
      case PostgresqlDatabasePlatform if connectionMetadata.databaseMajorVersion <= 9 =>
        val columnType = column.getType.getTypeName match {
          case "BIGINT" => ColumnType("BIGSERIAL", None)
          case "INTEGER" => ColumnType("SERIAL", None)
        }
        val columnDefault = new DatabaseFunction(s"""nextval('"${postgresqlSeqName(column)}"'::regclass)""")
        ColumnDefault(columnType, columnDefault)
      case _ => ColumnDefault(getColumnType(column, columnMapping), column.getDefaultValue)
    }
  }

  /**
    * Returns an optional extra check to ensure that datetimes can store microseconds.
    *
    * This is to double check for MySQL:
    *
    * > ... the default precision is 0.
    * > This differs from the standard SQL default of 6, for compatibility with previous MySQL versions.
    *
    * via: https://dev.mysql.com/doc/refman/8.0/en/fractional-seconds.html
    *
    * And for MariaDB:
    *
    * > The microsecond precision can be from 0-6. If not specified 0 is used.
    *
    * via: https://mariadb.com/kb/en/library/datetime/
    *
    * This check also has to be done here, as Liquibase does not return the precision for Mysql datetime fields.
    */
  private def columnTypeValidationOption(column: Column, databaseSystem: DatabaseSystem): Option[String] = {
    databaseSystem.platform match {
      case MysqlDatabasePlatform | MariadbDatabasePlatform if column.getType.getTypeName == "TIMESTAMP" =>
        Option("datetime(6)")
      case _ => None
    }
  }

  private def columnTypeDbio(column: Column,
                             databaseSystem: DatabaseSystem,
                             database: SlickDatabase): database.dataAccess.driver.api.DBIO[String] = {
    import database.dataAccess.driver.api._
    databaseSystem.platform match {
      case MysqlDatabasePlatform | MariadbDatabasePlatform if column.getType.getTypeName == "TIMESTAMP" =>
        val getType = GetResult(_.rs.getString("Type"))

        //noinspection SqlDialectInspection
        sql"""SHOW COLUMNS
              FROM #${column.getRelation.getName}
              WHERE FIELD = '#${column.getName}'
           """.as[String](getType).head
      case _ => DBIO.failed(unsupportedColumnTypeException(column, databaseSystem))
    }
  }

  /**
    * Returns an optional extra check to ensure that sequences have the same types as their auto increment columns.
    *
    * This is because PostgreSQL <= 9 requires two statements to modify SERIAL columns to BIGSERIAL, one to widen the
    * column, and another to widen the sequence.
    *
    * https://stackoverflow.com/questions/52195303/postgresql-primary-key-id-datatype-from-serial-to-bigserial#answer-52195920
    * https://www.postgresql.org/docs/11/datatype-numeric.html#DATATYPE-SERIAL
    */
  private def sequenceTypeValidationOption(column: Column,
                                           databaseSystem: DatabaseSystem,
                                           connectionMetadata: ConnectionMetadata,
                                          ): Option[String] = {
    databaseSystem.platform match {
      case PostgresqlDatabasePlatform if column.isAutoIncrement && connectionMetadata.databaseMajorVersion <= 9 =>
        // "this is currently always bigint" --> https://www.postgresql.org/docs/9.6/infoschema-sequences.html
        Option("bigint")
      case _ => None
    }
  }

  private def sequenceTypeDbio(column: Column,
                               databaseSystem: DatabaseSystem,
                               database: SlickDatabase): database.dataAccess.driver.api.DBIO[String] = {
    import database.dataAccess.driver.api._
    databaseSystem.platform match {
      case PostgresqlDatabasePlatform if column.isAutoIncrement =>
          //noinspection SqlDialectInspection
          sql"""select data_type
              from INFORMATION_SCHEMA.sequences
              where sequence_name = '#${postgresqlSeqName(column)}'
           """.as[String].head
      case _ => DBIO.failed(unsupportedColumnTypeException(column, databaseSystem))
    }
  }

  private def unsupportedColumnTypeException(column: Column,
                                             databaseSystem: DatabaseSystem): UnsupportedOperationException = {
    new UnsupportedOperationException(
      s"${databaseSystem.name} ${column.getRelation.getName}.${column.getName}: ${column.getType.getTypeName}"
    )
  }

  private val SQLiteUniqueConstraintRegex =
    """"(UC_[A-Z_]+)" UNIQUE \(([^)]+)\)[,)]""".r

  private val SQLiteForeignKeyRegex =
    """"(FK_[A-Z_]+)" FOREIGN KEY \("([A-Z_]+)"\) REFERENCES "([A-Z_]+)"\("([A-Z_]+)"\)(| ON DELETE CASCADE)[,)]""".r

  /**
    * Converts the SQL to create a SQLite table into a constraint.
    *
    * @param parseConstraint A method that's called repeatedly for each "CONSTRAINT" found in the SQL.
    * @param createTableSql The SQL to create a table.
    * @tparam T Type to convert the SQL to.
    * @return A sequence of converted types that were found.
    */
  private def convertSqliteTableSql[T](parseConstraint: (String, String) => Option[T])
                                      (createTableSql: String): Seq[T] = {
    val splits = createTableSql.split(" CONSTRAINT ")
    val tableName = splits.head.split(" ")(2).replaceAll("\"", "")
    splits.tail.flatMap(parseConstraint(tableName, _))
  }

  /**
    * Parses a "CONSTRAINT" section of the SQL used to create a SQLite table and optionally returns a UniqueConstraint.
    *
    * While unique indexes may be retrieved using SQLite pragmas, this is the only known way to retrieve unique
    * constraints from SQLite.
    *
    * https://stackoverflow.com/questions/9636053/is-there-a-way-to-get-the-constraints-of-a-table-in-sqlite#answer-12142499
    *
    * @param tableName The name of the table.
    * @param constraintSql a "CONSTRAINT" section of the SQL used to create a SQLite table.
    * @return An optional UniqueConstraint if found in the string.
    */
  private def toSqliteUC(tableName: String, constraintSql: String): Option[UniqueConstraint] = {
    constraintSql match {
      case SQLiteUniqueConstraintRegex(constraintName, columnString) =>
        // Remove the quotes and spaces, leaving just the names and commas, then split on the commas
        val columnNames = columnString.replaceAll("""[ "]""", "").split(",")
        val columns = columnNames.map(new Column(_))
        Option(
          new UniqueConstraint()
          .setName(constraintName)
          .setRelation(new Table().setName(tableName))
          .setColumns(columns.toList.asJava)
        )
      case _ => None
    }
  }

  /**
    * Parses a "CONSTRAINT" section of the SQL used to create a SQLite table and optionally returns a ForeignKey.
    *
    * The SQLite JDBC driver does not expect names to be quoted thus its internal regex fails to detect the quoted
    * names in the SQL used to create a SQLite table, and ends up always returning the empty string.
    *
    * https://github.com/xerial/sqlite-jdbc/blob/3.32.3.2/src/main/java/org/sqlite/jdbc3/JDBC3DatabaseMetaData.java#L2066-L2071
    * https://github.com/xerial/sqlite-jdbc/blob/3.32.3.2/src/main/java/org/sqlite/jdbc3/JDBC3DatabaseMetaData.java#L2144-L2146
    * https://github.com/xerial/sqlite-jdbc/blob/3.32.3.2/src/main/java/org/sqlite/jdbc3/JDBC3DatabaseMetaData.java#L1502-L1506
    *
    * @param foreignTableName The name of the table with the foreign key column.
    * @param constraintSql a "CONSTRAINT" section of the SQL used to create a SQLite table.
    * @return An optional ForeignKey if found in the string.
    */
  private def toSqliteFK(foreignTableName: String, constraintSql: String): Option[ForeignKey] = {
    constraintSql match {
      case SQLiteForeignKeyRegex(
      foreignKeyName,
      foreignColumnName,
      primaryTableName,
      primaryColumnName,
      cascadeDelete) =>
        val deleteRule =
          if (cascadeDelete.isEmpty) {
            ForeignKeyConstraintType.importedKeyRestrict
          } else {
            ForeignKeyConstraintType.importedKeyCascade
          }
        Option(
          new ForeignKey()
            .setName(foreignKeyName)
            .setForeignKeyTable(new Table().setName(foreignTableName))
            .setPrimaryKeyTable(new Table().setName(primaryTableName))
            .setForeignKeyColumns(List(new Column().setName(foreignColumnName)).asJava)
            .setPrimaryKeyColumns(List(new Column().setName(primaryColumnName)).asJava)
            .setDeleteRule(deleteRule)
            .setUpdateRule(ForeignKeyConstraintType.importedKeyRestrict)
            .setDeferrable(false)
            .setInitiallyDeferred(false)
        )
      case _ => None
    }
  }

  /** Retrieve the "sql" SQLite uses to create tables, then convert it to some type T. */
  private def sqliteTableSqlDbio[T](database: SlickDatabase,
                                    toConstraint: (String, String) => Option[T])
                                   (implicit executionContext: ExecutionContext)
  : database.dataAccess.driver.api.DBIO[Seq[T]] = {
    import database.dataAccess.driver.api._
    val getType = GetResult(_.rs.getString("sql"))

    //noinspection SqlDialectInspection
    sql"""SELECT sql
          FROM sqlite_master
          WHERE type = 'table'
          AND name NOT LIKE 'sqlite_%'
       """.as[String](getType).map(_.flatMap(convertSqliteTableSql(toConstraint)))
  }

  /** Optionally retrieve the unique constraints directly from the database. */
  private def uniqueConstraintDbio(databaseSystem: DatabaseSystem,
                                   database: SlickDatabase)
                                  (implicit executionContext: ExecutionContext)
  : database.dataAccess.driver.api.DBIO[Seq[UniqueConstraint]] = {
    import database.dataAccess.driver.api._
    databaseSystem.platform match {
      case SQLiteDatabasePlatform => sqliteTableSqlDbio(database, toSqliteUC)
      case _ => DBIO.successful(Nil)
    }
  }

  /** Optionally retrieve the foreign keys directly from the database. */
  private def foreignKeyDbio(databaseSystem: DatabaseSystem,
                                   database: SlickDatabase)
                                  (implicit executionContext: ExecutionContext)
  : database.dataAccess.driver.api.DBIO[Seq[ForeignKey]] = {
    import database.dataAccess.driver.api._
    databaseSystem.platform match {
      case SQLiteDatabasePlatform => sqliteTableSqlDbio(database, toSqliteFK)
      case _ => DBIO.successful(Nil)
    }
  }

  /**
    * Returns columns that are nullable, but shouldn't be.
    *
    * These nullables are not really hurting anything, but we should not add any more columns to this list.
    *
    * TODO: make a changelog to fix, and then remove list of mistakes.
    */
  private def getNullTodos(databaseSystem: DatabaseSystem,
                           databaseType: CromwellDatabaseType[_ <: SlickDatabase]): Seq[ColumnDescription] = {
    (databaseSystem.platform, databaseType) match {
      case (MysqlDatabasePlatform, EngineDatabaseType) =>
        List(
          ColumnDescription("CALL_CACHING_DETRITUS_ENTRY", "CALL_CACHING_ENTRY_ID"),
          ColumnDescription("CALL_CACHING_DETRITUS_ENTRY", "DETRITUS_KEY"),
          ColumnDescription("CALL_CACHING_ENTRY", "ALLOW_RESULT_REUSE"),
          ColumnDescription("CALL_CACHING_ENTRY", "CALL_FULLY_QUALIFIED_NAME"),
          ColumnDescription("CALL_CACHING_ENTRY", "JOB_ATTEMPT"),
          ColumnDescription("CALL_CACHING_ENTRY", "JOB_INDEX"),
          ColumnDescription("CALL_CACHING_ENTRY", "WORKFLOW_EXECUTION_UUID"),
          ColumnDescription("CALL_CACHING_HASH_ENTRY", "CALL_CACHING_ENTRY_ID"),
          ColumnDescription("CALL_CACHING_SIMPLETON_ENTRY", "CALL_CACHING_ENTRY_ID"),
          ColumnDescription("DOCKER_HASH_STORE_ENTRY", "DOCKER_SIZE"),
          ColumnDescription("JOB_KEY_VALUE_ENTRY", "CALL_FULLY_QUALIFIED_NAME"),
          ColumnDescription("JOB_KEY_VALUE_ENTRY", "JOB_ATTEMPT"),
          ColumnDescription("JOB_KEY_VALUE_ENTRY", "JOB_INDEX"),
          ColumnDescription("DOCKER_HASH_STORE_ENTRY", "DOCKER_SIZE"),
          ColumnDescription("JOB_STORE_ENTRY", "CALL_FULLY_QUALIFIED_NAME"),
          ColumnDescription("JOB_STORE_ENTRY", "JOB_ATTEMPT"),
          ColumnDescription("JOB_STORE_ENTRY", "JOB_INDEX"),
          ColumnDescription("JOB_STORE_ENTRY", "RETRYABLE_FAILURE"),
          ColumnDescription("JOB_STORE_ENTRY", "WORKFLOW_EXECUTION_UUID"),
          ColumnDescription("JOB_STORE_SIMPLETON_ENTRY", "JOB_STORE_ENTRY_ID"),
          ColumnDescription("WORKFLOW_STORE_ENTRY", "IMPORTS_ZIP"),
          ColumnDescription("WORKFLOW_STORE_ENTRY", "WORKFLOW_EXECUTION_UUID"),
          ColumnDescription("WORKFLOW_STORE_ENTRY", "WORKFLOW_STATE"),
        )
      case (MysqlDatabasePlatform, MetadataDatabaseType) =>
        List(
          ColumnDescription("CUSTOM_LABEL_ENTRY", "CUSTOM_LABEL_KEY"),
          ColumnDescription("CUSTOM_LABEL_ENTRY", "CUSTOM_LABEL_VALUE"),
          ColumnDescription("SUMMARY_STATUS_ENTRY", "SUMMARY_NAME"),
          ColumnDescription("SUMMARY_STATUS_ENTRY", "SUMMARY_POSITION"),
        )
      case _ => Nil
    }
  }
}
