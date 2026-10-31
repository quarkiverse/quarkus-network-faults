package org.acme.checkout;

import java.sql.SQLException;

public interface Orders {

    void place(String item) throws SQLException;
}
