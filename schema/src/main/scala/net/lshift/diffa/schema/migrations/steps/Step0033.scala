/**
 * Copyright (C) 2010-2012 LShift Ltd.
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
package net.lshift.diffa.schema.migrations.steps

import org.hibernate.cfg.Configuration
import net.lshift.hibernate.migrations.MigrationBuilder
import java.sql.Types
import net.lshift.diffa.schema.migrations.HibernateMigrationStep


object Step0033 extends HibernateMigrationStep {
  def versionId = 33
  def name = "Add the collation field to Endpoints"

  def createMigration(config: Configuration) = {
    val migration = new MigrationBuilder(config)

    migration.alterTable("endpoint").addColumn("collation_type", Types.VARCHAR, 16, false, "ascii")
    migration
  }
}
                                       p