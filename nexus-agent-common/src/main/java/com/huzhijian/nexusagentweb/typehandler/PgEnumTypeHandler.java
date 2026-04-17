package com.huzhijian.nexusagentweb.typehandler;

import com.huzhijian.nexusagentweb.em.UploadStatus;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

@MappedJdbcTypes(JdbcType.OTHER)
@MappedTypes(UploadStatus.class)
public class PgEnumTypeHandler extends BaseTypeHandler<UploadStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    UploadStatus parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter.name(), Types.OTHER);
    }

    @Override
    public UploadStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value == null ? null : UploadStatus.valueOf(value);
    }

    @Override
    public UploadStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return value == null ? null : UploadStatus.valueOf(value);
    }

    @Override
    public UploadStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return value == null ? null : UploadStatus.valueOf(value);
    }
}