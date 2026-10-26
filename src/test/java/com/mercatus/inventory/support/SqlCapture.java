package com.mercatus.inventory.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SqlCapture implements StatementInspector {

    private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public String inspect(String sql) {
        statements.get().add(sql.toLowerCase(Locale.ROOT));
        return sql;
    }

    public static void clear() {
        statements.remove();
    }

    public static List<String> statements() {
        return List.copyOf(statements.get());
    }
}
