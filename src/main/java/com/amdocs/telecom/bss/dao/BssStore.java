package com.amdocs.telecom.bss.dao;

import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** JDBC boundary: all values are bound, all resources closed, mutations transactional. */
public final class BssStore {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    @FunctionalInterface public interface Work<T> { T run(Tx tx) throws SQLException; }
    private final Connections connections;
    public BssStore(Connections connections) { this.connections = connections; }

    public <T> T transaction(Work<T> work) {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                T value = work.run(new Tx(c));
                c.commit();
                return value;
            } catch (Exception ex) {
                c.rollback();
                if (ex instanceof RuntimeException) throw (RuntimeException) ex;
                throw new IllegalStateException("Database operation failed; no changes saved", ex);
            }
        } catch (SQLException ex) {
            // Do not put connection strings, bound values, or driver error messages in user output.
            throw new IllegalStateException("Database unavailable or constraint rejected the operation", ex);
        }
    }

    public static final class Row {
        private final Map<String,Object> values;
        Row(Map<String,Object> values) { this.values = Collections.unmodifiableMap(values); }
        public String text(String key) { return Objects.toString(values.get(key), ""); }
        public long id(String key) { return ((Number) values.get(key)).longValue(); }
        public int integer(String key) { return ((Number) values.get(key)).intValue(); }
        public java.math.BigDecimal decimal(String key) { return new java.math.BigDecimal(text(key)); }
        public boolean flag(String key) { return Boolean.parseBoolean(text(key)) || "1".equals(text(key)); }
        public java.time.LocalDate date(String key) { return ((java.sql.Date)values.get(key)).toLocalDate(); }
        public java.time.LocalDateTime time(String key) { Object v=values.get(key); return v == null ? null : ((Timestamp)v).toLocalDateTime(); }
        public Map<String,Object> values() { return values; }
        @Override public String toString() { return values.toString(); }
    }

    public static final class Tx {
        private final Connection c;
        Tx(Connection c) { this.c = c; }
        private PreparedStatement prepare(String sql, Object... args) throws SQLException {
            PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            try {
                for (int i=0; i<args.length; i++) {
                    Object value = args[i];
                    if (value instanceof java.time.LocalDate) value=java.sql.Date.valueOf((java.time.LocalDate)value);
                    if (value instanceof java.time.LocalDateTime) value=Timestamp.valueOf((java.time.LocalDateTime)value);
                    s.setObject(i+1,value);
                }
                return s;
            } catch (SQLException ex) { s.close(); throw ex; }
        }
        public List<Row> query(String sql, Object... args) throws SQLException {
            try (PreparedStatement s=prepare(sql,args); ResultSet rs=s.executeQuery()) {
                List<Row> rows=new ArrayList<>();
                ResultSetMetaData md=rs.getMetaData();
                while(rs.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=md.getColumnCount();i++) row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
                    rows.add(new Row(row));
                }
                return rows;
            }
        }
        public Optional<Row> one(String sql,Object... args) throws SQLException { return query(sql,args).stream().findFirst(); }
        public Row required(String sql,Object... args) throws SQLException {
            return one(sql,args).orElseThrow(()->new IllegalArgumentException("Record not found or access denied"));
        }
        public int update(String sql,Object... args) throws SQLException {
            try(PreparedStatement s=prepare(sql,args)) { return s.executeUpdate(); }
        }
        public long insert(String sql,Object... args) throws SQLException {
            try(PreparedStatement s=prepare(sql,args)) {
                s.executeUpdate();
                try(ResultSet keys=s.getGeneratedKeys()) {
                    if(!keys.next()) throw new SQLException("Missing generated key");
                    return keys.getLong(1);
                }
            }
        }
        public int[] batch(String sql,List<Object[]> rows) throws SQLException {
            try(PreparedStatement s=c.prepareStatement(sql)) {
                for(Object[] row:rows) { for(int i=0;i<row.length;i++) s.setObject(i+1,row[i]); s.addBatch(); }
                return s.executeBatch();
            }
        }
        public Savepoint savepoint() throws SQLException { return c.setSavepoint(); }
        public void rollbackTo(Savepoint point) throws SQLException { c.rollback(point); }
    }
}
