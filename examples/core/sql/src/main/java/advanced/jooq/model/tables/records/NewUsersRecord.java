/*
 * This file is generated by jOOQ.
 */
package advanced.jooq.model.tables.records;


import advanced.jooq.model.tables.NewUsers;

import org.jooq.Record1;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class NewUsersRecord extends UpdatableRecordImpl<NewUsersRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>new_users.id</code>.
     */
    public void setId(Integer value) {
        set(0, value);
    }

    /**
     * Getter for <code>new_users.id</code>.
     */
    public Integer getId() {
        return (Integer) get(0);
    }

    /**
     * Setter for <code>new_users.first_name</code>.
     */
    public void setFirstName(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>new_users.first_name</code>.
     */
    public String getFirstName() {
        return (String) get(1);
    }

    /**
     * Setter for <code>new_users.last_name</code>.
     */
    public void setLastName(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>new_users.last_name</code>.
     */
    public String getLastName() {
        return (String) get(2);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Integer> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached NewUsersRecord
     */
    public NewUsersRecord() {
        super(NewUsers.NEW_USERS);
    }

    /**
     * Create a detached, initialised NewUsersRecord
     */
    public NewUsersRecord(Integer id, String firstName, String lastName) {
        super(NewUsers.NEW_USERS);

        setId(id);
        setFirstName(firstName);
        setLastName(lastName);
        resetChangedOnNotNull();
    }
}