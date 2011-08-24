/**
 * Copyright (C) 2011 LShift Ltd.
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
package net.lshift.hibernate.migrations;

import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.Dialect;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.PrimaryKey;
import org.hibernate.mapping.Table;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static net.lshift.hibernate.migrations.SQLStringHelpers.generateColumnString;
import static net.lshift.hibernate.migrations.SQLStringHelpers.qualifyName;

/**
 * Describes an alter table statement.
 */
public class AlterTableBuilder implements MigrationElement {
  private final Configuration config;
  private final Dialect dialect;
  private final String table;
  private final List<String> alterFragments;

  public AlterTableBuilder(Configuration config, Dialect dialect, String table) {
    this.config = config;
    this.dialect = dialect;
    this.table = table;
    this.alterFragments = new ArrayList<String>();
  }

  public AlterTableBuilder dropColumn(String column) {
    alterFragments.add("drop column " + dialect.openQuote() + column.toUpperCase() + dialect.openQuote());
    return this;
  }

  public AlterTableBuilder addColumn(String name, int sqlType, int length, boolean nullable, Object defaultVal) {
    Column col = new Column(name);
    col.setSqlTypeCode(sqlType);
    col.setNullable(nullable);
    col.setLength(length);
    col.setDefaultValue(defaultVal != null ? defaultVal.toString() : null);

    alterFragments.add("add column " + generateColumnString(dialect, col, false));
    return this;
  }

  public AlterTableBuilder addForeignKey(String name, String columnName, String referencedTable, String referencedColumn) {
    return addForeignKey(name, new String[] { columnName }, referencedTable, new String[] { referencedColumn });
  }
  public AlterTableBuilder addForeignKey(String name, String[] columnNames, String referencedTable, String[] referencedColumns) {
    ForeignKey fk = new ForeignKey();
    fk.setName(name);
    for (String col : columnNames) fk.addColumn(new Column(col));
    fk.setTable(new Table(table));

    PrimaryKey refPrimaryKey = new PrimaryKey();
    for (String col : referencedColumns) refPrimaryKey.addColumn(new Column(col));
    Table refTable = new Table(referencedTable);
    refTable.setPrimaryKey(refPrimaryKey);
    
    fk.setReferencedTable(refTable);

    String defaultCatalog = config.getProperties().getProperty(Environment.DEFAULT_CATALOG);
    String defaultSchema = config.getProperties().getProperty(Environment.DEFAULT_SCHEMA);
    
    alterFragments.add(fk.sqlConstraintString(dialect, fk.getName(), defaultCatalog, defaultSchema));
    return this;
  }

  @Override
  public void apply(Connection conn) throws SQLException {
    for (String fragment : alterFragments) {
      String sql = String.format("alter table %s %s", qualifyName(config, dialect, table), fragment);
      PreparedStatement stmt = conn.prepareStatement(sql);
      stmt.execute();
      stmt.close();
    }
  }
}