/**
 * Copyright (C) 2010-2011 LShift Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lshift.diffa.kernel.config

import org.hibernate.SessionFactory
import org.hibernate.jdbc.Work
import org.hibernate.tool.hbm2ddl.SchemaExport
import org.hibernate.dialect.Dialect
import org.slf4j.{LoggerFactory, Logger}
import net.lshift.diffa.kernel.util.SessionHelper._// for 'SessionFactory.withSession'
import org.hibernate.mapping.{Column, PrimaryKey}
import collection.mutable.ListBuffer
import java.sql.{Types, Connection}
import org.hibernate.cfg.Configuration

/**
 * Preparation step to ensure that the configuration for the Hibernate Config Store is in place.
 */
class HibernateConfigStorePreparationStep
    extends HibernatePreparationStep {

  val log:Logger = LoggerFactory.getLogger(getClass)

  // The migration steps necessary to bring a hibernate configuration up-to-date. Note that these steps should be
  // in strictly ascending order.
  val migrationSteps:Seq[HibernateMigrationStep] = Seq(
    RemoveGroupsMigrationStep,
    AddSchemaVersionMigrationStep
  )

  def prepare(sf: SessionFactory, config: Configuration) {

    detectVersion(sf) match {
      case None          => {
        (new SchemaExport(config)).create(false, true)

        // Since we are creating a fresh schema, we need to populate the schema version as well

        val createStmt = "create table schema_version (version integer not null, primary key (version))"
        val insertStmt = HibernatePreparationUtils.schemaVersionInsertStatement(migrationSteps.last.versionId)

        sf.withSession(s => {
          s.doWork(new Work() {
            def execute(connection: Connection) {
              val stmt = connection.createStatement()

              try {
                stmt.execute(createStmt)
                stmt.execute(insertStmt)
              } catch {
                case ex =>
                  println("Failed to prepare the schema_version table")
                  throw ex      // Higher level code will log the exception
              }

              stmt.close()
            }
          })
        })

        log.info("Applied initial database schema")
      }
      case Some(version) => {
        // Upgrade the schema if the current version is older than the last known migration step
        sf.withSession(s => {

          log.info("Current database version is " + version)

          val firstStepIdx = migrationSteps.indexWhere(step => step.versionId > version)
          if (firstStepIdx != -1) {
            s.doWork(new Work {
              def execute(connection: Connection) {
                migrationSteps.slice(firstStepIdx, migrationSteps.length).foreach(step => {
                  step.migrate(config, connection)
                  log.info("Upgraded database to version " + step.versionId)
                  if (step.versionId > 1) {
                    s.createSQLQuery(HibernatePreparationUtils.schemaVersionUpdateStatement(step.versionId)).executeUpdate()
                    s.flush
                  }
                })
              }
            })
          }
        })
      }
    }
  }

  /**
   * Detects the version of the schema using native SQL
   */
  def detectVersion(sf: SessionFactory) : Option[Int] = {
    try {
      // Attempt to read the schema_version table, if it exists
      Some(sf.withSession(_.createSQLQuery("select max(version) from schema_version").uniqueResult().asInstanceOf[Int]))
    }
    catch {
      case e => {
        // The schema_version table doesn't exist, so look at the config_options table
        try {
          //Prior to version 2 of the database, the schema version was kept in the ConfigOptions table
          val query = "select opt_val from config_options where opt_key = 'configStore.schemaVersion'"
          Some(sf.withSession(_.createSQLQuery(query).uniqueResult().asInstanceOf[String].toInt))
        }
        catch {
          // No table was available to read a schema version
          case e => None
        }
      }
    }
  }
}

/**
 * A set of helper functions to build portable SQL strings
 */
object HibernatePreparationUtils {

  /**
   * Generates a CREATE TABLE statement based on the descriptor and dialect
   */
  def generateCreateSQL(dialect:Dialect, descriptor:TableDescriptor) : String = {
    val buffer = new StringBuffer(dialect.getCreateTableString())
        .append(' ').append(dialect.quote(descriptor.name)).append(" (")

    descriptor.columns.foreach(col => {
      buffer.append(col.getName).append(" ")
      // TODO This doesn't handle length or precision yet, but it could
      buffer.append(dialect.getTypeName(col.getSqlTypeCode))
      if (!col.isNullable) {
        buffer.append(" not null")
      }
      buffer.append(", ")
    })

    buffer.append(descriptor.primaryKey.sqlConstraintString(dialect))

    buffer.append(")")
    buffer.toString
  }

  /**
   * Generates a statement to insert a fresh schema version
   */
  def schemaVersionInsertStatement(version:Int) =  "insert into schema_version(version) values(%s)".format(version)

  /**
   * Generates a statement to update the schema_version table
   */
  def schemaVersionUpdateStatement(version:Int) =  "update schema_version set version = %s".format(version)
}

/**
 * Metadata that describes the attributes of a table
 */
case class TableDescriptor(name:String,
                           columns: ListBuffer[Column],
                           pk:String) {
  def this(name:String, pk:String) = this(name, ListBuffer[Column](), pk)

  def primaryKey = new PrimaryKey{addColumn(new Column(pk))}

  def addColumn(columnName:String, sqlType:Int, nullable:Boolean) = {
    columns += new Column(columnName){setNullable(nullable); setSqlTypeCode(sqlType)}
    this
  }
}

abstract class HibernateMigrationStep {

  /**
   * The version that this step gets the database to.
   */
  def versionId:Int

  /**
   * Requests that the step perform it's necessary migration.
   */
  def migrate(config:Configuration, connection:Connection)
}

object RemoveGroupsMigrationStep extends HibernateMigrationStep {
  def versionId = 1
  def migrate(config: Configuration, connection: Connection) {
    val dialect = Dialect.getDialect(config.getProperties)

    val stmt = connection.createStatement()
    stmt.execute("alter table pair drop column " + dialect.openQuote() + "NAME" + dialect.closeQuote())
    stmt.execute("drop table pair_group")
  }
}

object AddSchemaVersionMigrationStep extends HibernateMigrationStep {
  def versionId = 2
  def migrate(config: Configuration, connection: Connection) {
    val dialect = Dialect.getDialect(config.getProperties)

    val schemaVersion = new TableDescriptor("schema_version", "version")
    schemaVersion.addColumn("version", Types.INTEGER, false)

    val stmt = connection.createStatement()
    stmt.execute(HibernatePreparationUtils.generateCreateSQL(dialect, schemaVersion))
    stmt.execute(HibernatePreparationUtils.schemaVersionInsertStatement(versionId))
  }
}