package io.succinct.recordmaster;

import io.succinct.recordmaster.Field;

public final class CustomerFields {
    private CustomerFields() {}

    public static final Field<Customer, java.util.UUID> id =
        new Field<>("id", Customer::id);

    public static final Field<Customer, java.lang.String> email =
        new Field<>("email", Customer::email);

    public static final Field<Customer, java.lang.String> name =
        new Field<>("name", Customer::name);

    public static final Field<Customer, io.succinct.recordmaster.CustomerStatus> status =
        new Field<>("status", Customer::status);

    public static final Field<Customer, java.time.Instant> createdAt =
        new Field<>("createdAt", Customer::createdAt);
}
