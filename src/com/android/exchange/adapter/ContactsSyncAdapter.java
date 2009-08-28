/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.exchange.adapter;

import com.android.email.codec.binary.Base64;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.OperationApplicationException;
import android.content.ContentProviderOperation.Builder;
import android.content.Entity.NamedContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.SyncStateContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.Settings;
import android.provider.ContactsContract.SyncState;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Sync adapter for EAS Contacts
 *
 */
public class ContactsSyncAdapter extends AbstractSyncAdapter {

    private static final String TAG = "EasContactsSyncAdapter";
    private static final String SERVER_ID_SELECTION = RawContacts.SOURCE_ID + "=?";
    private static final String CLIENT_ID_SELECTION = RawContacts.SYNC1 + "=?";
    private static final String[] ID_PROJECTION = new String[] {RawContacts._ID};
    private static final String[] GROUP_PROJECTION = new String[] {Groups.SOURCE_ID};

    private static final int[] HOME_ADDRESS_TAGS = new int[] {Tags.CONTACTS_HOME_ADDRESS_CITY,
        Tags.CONTACTS_HOME_ADDRESS_COUNTRY,
        Tags.CONTACTS_HOME_ADDRESS_POSTAL_CODE,
        Tags.CONTACTS_HOME_ADDRESS_STATE,
        Tags.CONTACTS_HOME_ADDRESS_STREET};

    private static final int[] WORK_ADDRESS_TAGS = new int[] {Tags.CONTACTS_BUSINESS_ADDRESS_CITY,
        Tags.CONTACTS_BUSINESS_ADDRESS_COUNTRY,
        Tags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE,
        Tags.CONTACTS_BUSINESS_ADDRESS_STATE,
        Tags.CONTACTS_BUSINESS_ADDRESS_STREET};

    private static final int[] OTHER_ADDRESS_TAGS = new int[] {Tags.CONTACTS_HOME_ADDRESS_CITY,
        Tags.CONTACTS_OTHER_ADDRESS_COUNTRY,
        Tags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE,
        Tags.CONTACTS_OTHER_ADDRESS_STATE,
        Tags.CONTACTS_OTHER_ADDRESS_STREET};

    // Note: These constants are likely to change; they are internal to this class now, but
    // may end up in the provider.
    private static final int TYPE_EMAIL1 = 20;
    private static final int TYPE_EMAIL2 = 21;
    private static final int TYPE_EMAIL3 = 22;

    // We'll split email into two columns, the one that Contacts uses (just for the email address
    // portion, and another one (the one defined here) for the display name
    //private static final String EMAIL_DISPLAY_NAME = Data.SYNC1;

    private static final int TYPE_IM1 = 23;
    private static final int TYPE_IM2 = 24;
    private static final int TYPE_IM3 = 25;

    private static final int TYPE_WORK2 = 26;
    private static final int TYPE_HOME2 = 27;
    private static final int TYPE_CAR = 28;
    private static final int TYPE_COMPANY_MAIN = 29;
    private static final int TYPE_MMS = 30;
    private static final int TYPE_RADIO = 31;
    private static final int TYPE_ASSISTANT = 32;

    ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();

    android.accounts.Account mAccountManagerAccount;

    public ContactsSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);
    }

    @Override
    public boolean parse(InputStream is) throws IOException {
        EasContactsSyncParser p = new EasContactsSyncParser(is, this);
        return p.parse();
    }

    /**
     * We get our SyncKey from ContactsProvider.  If there's not one, we set it to "0" (the reset
     * state) and save that away.
     */
    @Override
    public String getSyncKey() throws IOException {
        ContentProviderClient client =
            mService.mContentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
        try {
            byte[] data = SyncStateContract.Helpers.get(client,
                    ContactsContract.SyncState.CONTENT_URI, getAccountManagerAccount());
            if (data == null || data.length == 0) {
                // Initialize the SyncKey
                setSyncKey("0", false);
                // Make sure ungrouped contacts for Exchange are defaultly visible
                ContentValues cv = new ContentValues();
                cv.put(Groups.ACCOUNT_NAME, mAccount.mEmailAddress);
                cv.put(Groups.ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE);
                cv.put(Settings.UNGROUPED_VISIBLE, true);
                client.insert(Settings.CONTENT_URI, cv);
                return "0";
            } else {
                String syncKey = new String(data);
                userLog("SyncKey retrieved from ContactsProvider: " + syncKey);
                return syncKey;
            }
        } catch (RemoteException e) {
            throw new IOException("Can't get SyncKey from ContactsProvider");
        }
    }

    /**
     * We only need to set this when we're forced to make the SyncKey "0" (a reset).  In all other
     * cases, the SyncKey is set within ContactOperations
     */
    @Override
    public void setSyncKey(String syncKey, boolean inCommands) throws IOException {
        if ("0".equals(syncKey) || !inCommands) {
            ContentProviderClient client =
                mService.mContentResolver
                    .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
            try {
                SyncStateContract.Helpers.set(client, ContactsContract.SyncState.CONTENT_URI,
                        getAccountManagerAccount(), syncKey.getBytes());
                userLog("SyncKey set to ", syncKey, " in ContactsProvider");
           } catch (RemoteException e) {
                throw new IOException("Can't set SyncKey in ContactsProvider");
            }
        }
        mMailbox.mSyncKey = syncKey;
    }

    public android.accounts.Account getAccountManagerAccount() {
        if (mAccountManagerAccount == null) {
            mAccountManagerAccount =
                new android.accounts.Account(mAccount.mEmailAddress, Eas.ACCOUNT_MANAGER_TYPE);
        }
        return mAccountManagerAccount;
    }

    public static final class EasChildren {
        private EasChildren() {}

        /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/eas_children";
        public static final int MAX_CHILDREN = 8;
        public static final String[] ROWS =
            new String[] {"data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9"};
    }

    public static final class EasPersonal {
        String anniversary;
        String birthday;
        String fileAs;

            /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/eas_personal";
        public static final String ANNIVERSARY = "data2";
        public static final String BIRTHDAY = "data3";
        public static final String FILE_AS = "data4";

        boolean hasData() {
            return anniversary != null || birthday != null || fileAs != null;
        }
    }

    public static final class EasBusiness {
        String officeLocation;
        String customerId;
        String governmentId;
        String accountName;

        /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/eas_business";
        public static final String OFFICE_LOCATION = "data4";
        public static final String CUSTOMER_ID = "data6";
        public static final String GOVERNMENT_ID = "data7";
        public static final String ACCOUNT_NAME = "data8";

        boolean hasData() {
            return officeLocation != null || customerId != null || governmentId != null
                || accountName != null;
        }
    }

    public static final class Address {
        String city;
        String country;
        String code;
        String street;
        String state;

        boolean hasData() {
            return city != null || country != null || code != null || state != null
                || street != null;
        }
    }

    class EasContactsSyncParser extends AbstractSyncParser {

        String[] mBindArgument = new String[1];
        String mMailboxIdAsString;
        Uri mAccountUri;
        ContactOperations ops = new ContactOperations();

        public EasContactsSyncParser(InputStream in, ContactsSyncAdapter adapter) throws IOException {
            super(in, adapter);
            mAccountUri = uriWithAccount(RawContacts.CONTENT_URI);
        }

        @Override
        public void wipe() {
            // TODO Use the bulk delete when the CP supports it
//            mContentResolver.delete(mAccountUri.buildUpon()
//                    .appendQueryParameter(ContactsContract.RawContacts.DELETE_PERMANENTLY, "true")
//                    .build(), null, null);
            Cursor c = mContentResolver.query(mAccountUri, new String[] {"_id"}, null, null, null);
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    mContentResolver.delete(ContentUris
                            .withAppendedId(mAccountUri, id)
                            .buildUpon().appendQueryParameter(
                                    ContactsContract.RawContacts.DELETE_PERMANENTLY, "true")
                            .build(), null, null);
                }
            } finally {
                c.close();
            }
        }

        public void addData(String serverId, ContactOperations ops, Entity entity)
                throws IOException {
            String prefix = null;
            String firstName = null;
            String lastName = null;
            String middleName = null;
            String suffix = null;
            String companyName = null;
            String yomiFirstName = null;
            String yomiLastName = null;
            String yomiCompanyName = null;
            String title = null;
            String department = null;
            Address home = new Address();
            Address work = new Address();
            Address other = new Address();
            EasBusiness business = new EasBusiness();
            EasPersonal personal = new EasPersonal();
            ArrayList<String> children = new ArrayList<String>();

            if (entity == null) {
                ops.newContact(serverId);
            }

            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case Tags.CONTACTS_FIRST_NAME:
                        firstName = getValue();
                        break;
                    case Tags.CONTACTS_LAST_NAME:
                        lastName = getValue();
                        break;
                    case Tags.CONTACTS_MIDDLE_NAME:
                        middleName = getValue();
                        break;
                    case Tags.CONTACTS_SUFFIX:
                        suffix = getValue();
                        break;
                    case Tags.CONTACTS_COMPANY_NAME:
                        companyName = getValue();
                        break;
                    case Tags.CONTACTS_JOB_TITLE:
                        title = getValue();
                        break;
                    case Tags.CONTACTS_EMAIL1_ADDRESS:
                        ops.addEmail(entity, TYPE_EMAIL1, getValue());
                        break;
                    case Tags.CONTACTS_EMAIL2_ADDRESS:
                        ops.addEmail(entity, TYPE_EMAIL2, getValue());
                        break;
                    case Tags.CONTACTS_EMAIL3_ADDRESS:
                        ops.addEmail(entity, TYPE_EMAIL3, getValue());
                        break;
                    case Tags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_WORK2, getValue());
                        break;
                    case Tags.CONTACTS_BUSINESS_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_WORK, getValue());
                        break;
                    case Tags.CONTACTS2_MMS:
                        ops.addPhone(entity, TYPE_MMS, getValue());
                        break;
                    case Tags.CONTACTS_BUSINESS_FAX_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_FAX_WORK, getValue());
                        break;
                    case Tags.CONTACTS2_COMPANY_MAIN_PHONE:
                        ops.addPhone(entity, TYPE_COMPANY_MAIN, getValue());
                        break;
                    case Tags.CONTACTS_HOME_FAX_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_FAX_HOME, getValue());
                        break;
                    case Tags.CONTACTS_HOME_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_HOME, getValue());
                        break;
                    case Tags.CONTACTS_HOME2_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_HOME2, getValue());
                        break;
                    case Tags.CONTACTS_MOBILE_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_MOBILE, getValue());
                        break;
                    case Tags.CONTACTS_CAR_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_CAR, getValue());
                        break;
                    case Tags.CONTACTS_RADIO_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_RADIO, getValue());
                        break;
                    case Tags.CONTACTS_PAGER_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_PAGER, getValue());
                        break;
                    case Tags.CONTACTS_ASSISTANT_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_ASSISTANT, getValue());
                        break;
                    case Tags.CONTACTS2_IM_ADDRESS:
                        ops.addIm(entity, TYPE_IM1, getValue());
                        break;
                    case Tags.CONTACTS2_IM_ADDRESS_2:
                        ops.addIm(entity, TYPE_IM2, getValue());
                        break;
                    case Tags.CONTACTS2_IM_ADDRESS_3:
                        ops.addIm(entity, TYPE_IM3, getValue());
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_CITY:
                        work.city = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_COUNTRY:
                        work.country = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE:
                        work.code = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_STATE:
                        work.state = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_STREET:
                        work.street = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_CITY:
                        home.city = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_COUNTRY:
                        home.country = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_POSTAL_CODE:
                        home.code = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_STATE:
                        home.state = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_STREET:
                        home.street = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_CITY:
                        other.city = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_COUNTRY:
                        other.country = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE:
                        other.code = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_STATE:
                        other.state = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_STREET:
                        other.street = getValue();
                        break;

                    case Tags.CONTACTS_CHILDREN:
                        childrenParser(children);
                        break;

                    case Tags.CONTACTS_YOMI_COMPANY_NAME:
                        yomiCompanyName = getValue();
                        break;
                    case Tags.CONTACTS_YOMI_FIRST_NAME:
                        yomiFirstName = getValue();
                        break;
                    case Tags.CONTACTS_YOMI_LAST_NAME:
                        yomiLastName = getValue();
                        break;

                    case Tags.CONTACTS2_NICKNAME:
                        ops.addNickname(entity, getValue());
                        break;

                    case Tags.CONTACTS_ASSISTANT_NAME:
                        ops.addRelation(entity, Relation.TYPE_ASSISTANT, getValue());
                        break;
                    case Tags.CONTACTS2_MANAGER_NAME:
                        ops.addRelation(entity, Relation.TYPE_MANAGER, getValue());
                        break;
                    case Tags.CONTACTS_SPOUSE:
                        ops.addRelation(entity, Relation.TYPE_SPOUSE, getValue());
                        break;
                    case Tags.CONTACTS_DEPARTMENT:
                        department = getValue();
                        break;
                    case Tags.CONTACTS_TITLE:
                        prefix = getValue();
                        break;

                    // EAS Business
                    case Tags.CONTACTS_OFFICE_LOCATION:
                        business.officeLocation = getValue();
                        break;
                    case Tags.CONTACTS2_CUSTOMER_ID:
                        business.customerId = getValue();
                        break;
                    case Tags.CONTACTS2_GOVERNMENT_ID:
                        business.governmentId = getValue();
                        break;
                    case Tags.CONTACTS2_ACCOUNT_NAME:
                        business.accountName = getValue();
                        break;

                    // EAS Personal
                    case Tags.CONTACTS_ANNIVERSARY:
                        personal.anniversary = getValue();
                        break;
                    case Tags.CONTACTS_BIRTHDAY:
                        personal.birthday = getValue();
                        break;
                    case Tags.CONTACTS_FILE_AS:
                        personal.fileAs = getValue();
                        break;
                    case Tags.CONTACTS_WEBPAGE:
                        ops.addWebpage(entity, getValue());
                        break;

                    case Tags.CONTACTS_PICTURE:
                        ops.addPhoto(entity, getValue());
                        break;

                    case Tags.BASE_BODY:
                        ops.addNote(entity, bodyParser());
                        break;
                    case Tags.CONTACTS_BODY:
                        ops.addNote(entity, getValue());
                        break;

                    // TODO Handle Categories/Category
                    // If we don't handle this properly, we'll lose the information if/when we
                    // upload changes to the server!
                    case Tags.CONTACTS_CATEGORIES:
                        categoriesParser(ops, entity);
                        break;


                    case Tags.CONTACTS_COMPRESSED_RTF:
                        // We don't use this, and it isn't necessary to upload, so we'll ignore it
                        skipTag();
                        break;

                    default:
                        skipTag();
                }
            }

            // We must have first name, last name, or company name
            String name;
            if (firstName != null || lastName != null) {
                if (firstName == null) {
                    name = lastName;
                } else if (lastName == null) {
                    name = firstName;
                } else {
                    name = firstName + ' ' + lastName;
                }
            } else if (companyName != null) {
                name = companyName;
            } else {
                return;
            }

            ops.addName(entity, prefix, firstName, lastName, middleName, suffix, name,
                    yomiFirstName, yomiLastName);
            ops.addBusiness(entity, business);
            ops.addPersonal(entity, personal);

            if (!children.isEmpty()) {
                ops.addChildren(entity, children);
            }

            if (work.hasData()) {
                ops.addPostal(entity, StructuredPostal.TYPE_WORK, work.street, work.city,
                        work.state, work.country, work.code);
            }
            if (home.hasData()) {
                ops.addPostal(entity, StructuredPostal.TYPE_HOME, home.street, home.city,
                        home.state, home.country, home.code);
            }
            if (other.hasData()) {
                ops.addPostal(entity, StructuredPostal.TYPE_OTHER, other.street, other.city,
                        other.state, other.country, other.code);
            }

            if (companyName != null) {
                ops.addOrganization(entity, Organization.TYPE_WORK, companyName, title, department,
                        yomiCompanyName);
            }

            if (entity != null) {
                // We've been removing rows from the list as they've been found in the xml
                // Any that are left must have been deleted on the server
                ArrayList<NamedContentValues> ncvList = entity.getSubValues();
                for (NamedContentValues ncv: ncvList) {
                    // These rows need to be deleted...
                    Uri u = dataUriFromNamedContentValues(ncv);
                    ops.add(ContentProviderOperation.newDelete(u).build());
                }
            }
        }

        private void categoriesParser(ContactOperations ops, Entity entity) throws IOException {
            while (nextTag(Tags.CONTACTS_CATEGORIES) != END) {
                switch (tag) {
                    case Tags.CONTACTS_CATEGORY:
                        ops.addGroup(entity, getValue());
                    default:
                        skipTag();
                }
            }
        }

        private void childrenParser(ArrayList<String> children) throws IOException {
            while (nextTag(Tags.CONTACTS_CHILDREN) != END) {
                switch (tag) {
                    case Tags.CONTACTS_CHILD:
                        if (children.size() < EasChildren.MAX_CHILDREN) {
                            children.add(getValue());
                        }
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private String bodyParser() throws IOException {
            String body = null;
            while (nextTag(Tags.BASE_BODY) != END) {
                switch (tag) {
                    case Tags.BASE_DATA:
                        body = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            return body;
        }

        public void addParser(ContactOperations ops) throws IOException {
            String serverId = null;
            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID: // same as
                        serverId = getValue();
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops, null);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private Cursor getServerIdCursor(String serverId) {
            mBindArgument[0] = serverId;
            return mContentResolver.query(mAccountUri, ID_PROJECTION, SERVER_ID_SELECTION,
                    mBindArgument, null);
        }

        private Cursor getClientIdCursor(String clientId) {
            mBindArgument[0] = clientId;
            return mContentResolver.query(mAccountUri, ID_PROJECTION, CLIENT_ID_SELECTION,
                    mBindArgument, null);
        }

        public void deleteParser(ContactOperations ops) throws IOException {
            while (nextTag(Tags.SYNC_DELETE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the message in this mailbox with the given serverId
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                userLog("Deleting ", serverId);
                                ops.delete(c.getLong(0));
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    default:
                        skipTag();
                }
            }
        }

        class ServerChange {
            long id;
            boolean read;

            ServerChange(long _id, boolean _read) {
                id = _id;
                read = _read;
            }
        }

        /**
         * Changes are handled row by row, and only changed/new rows are acted upon
         * @param ops the array of pending ContactProviderOperations.
         * @throws IOException
         */
        public void changeParser(ContactOperations ops) throws IOException {
            String serverId = null;
            Entity entity = null;
            while (nextTag(Tags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                // TODO Handle deleted individual rows...
                                try {
                                    EntityIterator entityIterator =
                                        mContentResolver.queryEntities(ContentUris
                                            .withAppendedId(RawContacts.CONTENT_URI, c.getLong(0)),
                                            null, null, null);
                                    if (entityIterator.hasNext()) {
                                        entity = entityIterator.next();
                                    }
                                    userLog("Changing contact ", serverId);
                                } catch (RemoteException e) {
                                }
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops, entity);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        @Override
        public void commandsParser() throws IOException {
            while (nextTag(Tags.SYNC_COMMANDS) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addParser(ops);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_DELETE) {
                    deleteParser(ops);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_CHANGE) {
                    changeParser(ops);
                    incrementChangeCount();
                } else
                    skipTag();
            }
        }

        @Override
        public void commit() throws IOException {
           // Save the syncKey here, using the Helper provider by Contacts provider
            userLog("Contacts SyncKey saved as: ", mMailbox.mSyncKey);
            ops.add(SyncStateContract.Helpers.newSetOperation(SyncState.CONTENT_URI,
                    getAccountManagerAccount(), mMailbox.mSyncKey.getBytes()));

            // Execute these all at once...
            ops.execute();

            if (ops.mResults != null) {
                ContentValues cv = new ContentValues();
                cv.put(RawContacts.DIRTY, 0);
                for (int i = 0; i < ops.mContactIndexCount; i++) {
                    int index = ops.mContactIndexArray[i];
                    Uri u = ops.mResults[index].uri;
                    if (u != null) {
                        String idString = u.getLastPathSegment();
                        mContentResolver.update(RawContacts.CONTENT_URI, cv,
                                RawContacts._ID + "=" + idString, null);
                    }
                }
            }
        }

        public void addResponsesParser() throws IOException {
            String serverId = null;
            String clientId = null;
            ContentValues cv = new ContentValues();
            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        break;
                    case Tags.SYNC_CLIENT_ID:
                        clientId = getValue();
                        break;
                    case Tags.SYNC_STATUS:
                        getValue();
                        break;
                    default:
                        skipTag();
                }
            }

            // This is theoretically impossible, but...
            if (clientId == null || serverId == null) return;

            Cursor c = getClientIdCursor(clientId);
            try {
                if (c.moveToFirst()) {
                    cv.put(RawContacts.SOURCE_ID, serverId);
                    cv.put(RawContacts.DIRTY, 0);
                    ops.add(ContentProviderOperation.newUpdate(ContentUris
                            .withAppendedId(RawContacts.CONTENT_URI, c.getLong(0)))
                            .withValues(cv)
                            .build());
                    userLog("New contact " + clientId + " was given serverId: " + serverId);
                }
            } finally {
                c.close();
            }
        }
        @Override
        public void responsesParser() throws IOException {
            // Handle server responses here (for Add and Change)
            while (nextTag(Tags.SYNC_RESPONSES) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addResponsesParser();
                } else if (tag == Tags.SYNC_CHANGE) {
                    //changeResponsesParser();
                } else
                    skipTag();
            }
        }
    }


    private Uri uriWithAccount(Uri uri) {
        return uri.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, mAccount.mEmailAddress)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE)
            .build();
    }

    /**
     * SmartBuilder is a wrapper for the Builder class that is used to create/update rows for a
     * ContentProvider.  It has, in addition to the Builder, ContentValues which, if present,
     * represent the current values of that row, that can be compared against current values to
     * see whether an update is even necessary.  The methods on SmartBuilder are delegated to
     * the Builder.
     */
    private class SmartBuilder {
        Builder builder;
        ContentValues cv;

        public SmartBuilder(Builder _builder) {
            builder = _builder;
        }

        public SmartBuilder(Builder _builder, NamedContentValues _ncv) {
            builder = _builder;
            cv = _ncv.values;
        }

        SmartBuilder withValues(ContentValues values) {
            builder.withValues(values);
            return this;
        }

        SmartBuilder withValueBackReference(String key, int previousResult) {
            builder.withValueBackReference(key, previousResult);
            return this;
        }

        ContentProviderOperation build() {
            return builder.build();
        }

        SmartBuilder withValue(String key, Object value) {
            builder.withValue(key, value);
            return this;
        }
    }

    private class ContactOperations extends ArrayList<ContentProviderOperation> {
        private static final long serialVersionUID = 1L;
        private int mCount = 0;
        private int mContactBackValue = mCount;
        // Make an array big enough for the PIM window (max items we can get)
        private int[] mContactIndexArray =
            new int[Integer.parseInt(EasSyncService.PIM_WINDOW_SIZE)];
        private int mContactIndexCount = 0;
        private ContentProviderResult[] mResults = null;

        @Override
        public boolean add(ContentProviderOperation op) {
            super.add(op);
            mCount++;
            return true;
        }

        public void newContact(String serverId) {
            Builder builder = ContentProviderOperation
                .newInsert(uriWithAccount(RawContacts.CONTENT_URI));
            ContentValues values = new ContentValues();
            values.put(RawContacts.SOURCE_ID, serverId);
            builder.withValues(values);
            mContactBackValue = mCount;
            mContactIndexArray[mContactIndexCount++] = mCount;
            add(builder.build());
        }

        public void delete(long id) {
            add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
                            .buildUpon()
                            .appendQueryParameter(ContactsContract.RawContacts.DELETE_PERMANENTLY,
                                    "true")
                            .build())
                    .build());
        }

        public void execute() {
            synchronized (mService.getSynchronizer()) {
                if (!mService.isStopped()) {
                    try {
                        mService.userLog("Executing ", size(), " CPO's");
                        mResults = mContext.getContentResolver().applyBatch(
                                ContactsContract.AUTHORITY, this);
                    } catch (RemoteException e) {
                        // There is nothing sensible to be done here
                        Log.e(TAG, "problem inserting contact during server update", e);
                    } catch (OperationApplicationException e) {
                        // There is nothing sensible to be done here
                        Log.e(TAG, "problem inserting contact during server update", e);
                    }
                }
            }
        }

        /**
         * Given the list of NamedContentValues for an entity, a mime type, and a subtype,
         * tries to find a match, returning it
         * @param list the list of NCV's from the contact entity
         * @param contentItemType the mime type we're looking for
         * @param type the subtype (e.g. HOME, WORK, etc.)
         * @return the matching NCV or null if not found
         */
        private NamedContentValues findExistingData(ArrayList<NamedContentValues> list,
                String contentItemType, int type, String stringType) {
            NamedContentValues result = null;

            // Loop through the ncv's, looking for an existing row
            for (NamedContentValues namedContentValues: list) {
                Uri uri = namedContentValues.uri;
                ContentValues cv = namedContentValues.values;
                if (Data.CONTENT_URI.equals(uri)) {
                    String mimeType = cv.getAsString(Data.MIMETYPE);
                    if (mimeType.equals(contentItemType)) {
                        if (stringType != null) {
                            if (cv.getAsString(GroupMembership.GROUP_ROW_ID).equals(stringType)) {
                                result = namedContentValues;
                            }
                        // Note Email.TYPE could be ANY type column; they are all defined in
                        // the private CommonColumns class in ContactsContract
                        } else if (type < 0 || cv.getAsInteger(Email.TYPE) == type) {
                            result = namedContentValues;
                        }
                    }
                }
            }

            // TODO Handle deleted items
            // If we've found an existing data row, we'll delete it.  Any rows left at the
            // end should be deleted...
            if (result != null) {
                list.remove(result);
            }

            // Return the row found (or null)
            return result;
        }

        /**
         * Create a wrapper for a builder (insert or update) that also includes the NCV for
         * an existing row of this type.   If the SmartBuilder's cv field is not null, then
         * it represents the current (old) values of this field.  The caller can then check
         * whether the field is now different and needs to be updated; if it's not different,
         * the caller will simply return and not generate a new CPO.  Otherwise, the builder
         * should have its content values set, and the built CPO should be added to the
         * ContactOperations list.
         *
         * @param entity the contact entity (or null if this is a new contact)
         * @param mimeType the mime type of this row
         * @param type the subtype of this row
         * @param stringType for groups, the name of the group (type will be ignored), or null
         * @return the created SmartBuilder
         */
        public SmartBuilder createBuilder(Entity entity, String mimeType, int type) {
            return createBuilder(entity, mimeType, type, null);
        }

        public SmartBuilder createBuilder(Entity entity, String mimeType, int type,
                String stringType) {
            int contactId = mContactBackValue;
            SmartBuilder builder = null;

            if (entity != null) {
                NamedContentValues ncv =
                    findExistingData(entity.getSubValues(), mimeType, type, stringType);
                if (ncv != null) {
                    builder = new SmartBuilder(
                            ContentProviderOperation
                                .newUpdate(dataUriFromNamedContentValues(ncv)),
                            ncv);
                } else {
                    contactId = entity.getEntityValues().getAsInteger(RawContacts._ID);
                }
            }

            if (builder == null) {
                builder =
                    new SmartBuilder(ContentProviderOperation.newInsert(Data.CONTENT_URI));
                if (entity == null) {
                    builder.withValueBackReference(Data.RAW_CONTACT_ID, contactId);
                } else {
                    builder.withValue(Data.RAW_CONTACT_ID, contactId);
                }

                builder.withValue(Data.MIMETYPE, mimeType);
            }

            // Return the appropriate builder (insert or update)
            // Caller will fill in the appropriate values; 4 MIMETYPE is already set
            return builder;
        }

        /**
         * Compare a column in a ContentValues with an (old) value, and see if they are the
         * same.  For this purpose, null and an empty string are considered the same.
         * @param cv a ContentValues object, from a NamedContentValues
         * @param column a column that might be in the ContentValues
         * @param oldValue an old value (or null) to check against
         * @return whether the column's value in the ContentValues matches oldValue
         */
        private boolean cvCompareString(ContentValues cv, String column, String oldValue) {
            if (cv.containsKey(column)) {
                if (oldValue != null && cv.getAsString(column).equals(oldValue)) {
                    return true;
                }
            } else if (oldValue == null || oldValue.length() == 0) {
                return true;
            }
            return false;
        }

        public void addEmail(Entity entity, int type, String email) {
            SmartBuilder builder = createBuilder(entity, Email.CONTENT_ITEM_TYPE, type);
            Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(email);
            // Can't happen, but belt & suspenders
            if (tokens.length == 0) {
                return;
            }
            Rfc822Token token = tokens[0];
            String addr = token.getAddress();
            String name = token.getName();
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Email.DATA, addr)
                    && cvCompareString(cv, Email.DISPLAY_NAME, name)) {
                return;
            }
            builder.withValue(Email.TYPE, type);
            builder.withValue(Email.DATA, addr);
            builder.withValue(Email.DISPLAY_NAME, name);
            add(builder.build());
        }

        public void addChildren(Entity entity, ArrayList<String> children) {
            SmartBuilder builder = createBuilder(entity, EasChildren.CONTENT_ITEM_TYPE, -1);
            int i = 0;
            for (String child: children) {
                builder.withValue(EasChildren.ROWS[i++], child);
            }
            add(builder.build());
        }

        public void addGroup(Entity entity, String group) {
            SmartBuilder builder =
                createBuilder(entity, GroupMembership.CONTENT_ITEM_TYPE, -1, group);
            builder.withValue(GroupMembership.GROUP_SOURCE_ID, group);
            add(builder.build());
        }

        public void addName(Entity entity, String prefix, String givenName, String familyName,
                String middleName, String suffix, String displayName, String yomiFirstName,
                String yomiLastName) {
            SmartBuilder builder = createBuilder(entity, StructuredName.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, StructuredName.GIVEN_NAME, givenName) &&
                    cvCompareString(cv, StructuredName.FAMILY_NAME, familyName) &&
                    cvCompareString(cv, StructuredName.MIDDLE_NAME, middleName) &&
                    cvCompareString(cv, StructuredName.PREFIX, prefix) &&
                    cvCompareString(cv, StructuredName.PHONETIC_GIVEN_NAME, yomiFirstName) &&
                    cvCompareString(cv, StructuredName.PHONETIC_FAMILY_NAME, yomiLastName) &&
                    cvCompareString(cv, StructuredName.SUFFIX, suffix)) {
                return;
            }
            builder.withValue(StructuredName.GIVEN_NAME, givenName);
            builder.withValue(StructuredName.FAMILY_NAME, familyName);
            builder.withValue(StructuredName.MIDDLE_NAME, middleName);
            builder.withValue(StructuredName.SUFFIX, suffix);
            builder.withValue(StructuredName.PHONETIC_GIVEN_NAME, yomiFirstName);
            builder.withValue(StructuredName.PHONETIC_FAMILY_NAME, yomiLastName);
            builder.withValue(StructuredName.PREFIX, prefix);
            add(builder.build());
        }

        public void addPersonal(Entity entity, EasPersonal personal) {
            SmartBuilder builder = createBuilder(entity, EasPersonal.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, EasPersonal.ANNIVERSARY, personal.anniversary) &&
                    cvCompareString(cv, EasPersonal.BIRTHDAY, personal.birthday) &&
                    cvCompareString(cv, EasPersonal.FILE_AS , personal.fileAs)) {
                return;
            }
            if (!personal.hasData()) {
                return;
            }
            builder.withValue(EasPersonal.BIRTHDAY, personal.birthday);
            builder.withValue(EasPersonal.FILE_AS, personal.fileAs);
            builder.withValue(EasPersonal.ANNIVERSARY, personal.anniversary);
            add(builder.build());
        }

        public void addBusiness(Entity entity, EasBusiness business) {
            SmartBuilder builder = createBuilder(entity, EasBusiness.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, EasBusiness.ACCOUNT_NAME, business.accountName) &&
                    cvCompareString(cv, EasBusiness.CUSTOMER_ID, business.customerId) &&
                    cvCompareString(cv, EasBusiness.GOVERNMENT_ID, business.governmentId) &&
                    cvCompareString(cv, EasBusiness.OFFICE_LOCATION, business.officeLocation)) {
                return;
            }
            if (!business.hasData()) {
                return;
            }
            builder.withValue(EasBusiness.ACCOUNT_NAME, business.accountName);
            builder.withValue(EasBusiness.CUSTOMER_ID, business.customerId);
            builder.withValue(EasBusiness.GOVERNMENT_ID, business.governmentId);
            builder.withValue(EasBusiness.OFFICE_LOCATION, business.officeLocation);
            add(builder.build());
        }

        public void addPhoto(Entity entity, String photo) {
            SmartBuilder builder = createBuilder(entity, Photo.CONTENT_ITEM_TYPE, -1);
            // We're always going to add this; it's not worth trying to figure out whether the
            // picture is the same as the one stored.
            byte[] pic = Base64.decodeBase64(photo.getBytes());
            builder.withValue(Photo.PHOTO, pic);
            add(builder.build());
        }

        public void addPhone(Entity entity, int type, String phone) {
            SmartBuilder builder = createBuilder(entity, Phone.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Phone.NUMBER, phone)) {
                return;
            }
            builder.withValue(Phone.TYPE, type);
            builder.withValue(Phone.NUMBER, phone);
            add(builder.build());
        }

        public void addWebpage(Entity entity, String url) {
            SmartBuilder builder = createBuilder(entity, Website.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Website.URL, url)) {
                return;
            }
            builder.withValue(Website.TYPE, Website.TYPE_WORK);
            builder.withValue(Website.URL, url);
            add(builder.build());
        }

        public void addRelation(Entity entity, int type, String value) {
            SmartBuilder builder = createBuilder(entity, Relation.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Relation.DATA, value)) {
                return;
            }
            builder.withValue(Relation.TYPE, type);
            builder.withValue(Relation.DATA, value);
            add(builder.build());
        }

        public void addNickname(Entity entity, String name) {
            SmartBuilder builder =
                createBuilder(entity, Nickname.CONTENT_ITEM_TYPE, Nickname.TYPE_DEFAULT);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Nickname.NAME, name)) {
                return;
            }
            builder.withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT);
            builder.withValue(Nickname.NAME, name);
            add(builder.build());
        }

        public void addPostal(Entity entity, int type, String street, String city, String state,
                String country, String code) {
            SmartBuilder builder = createBuilder(entity, StructuredPostal.CONTENT_ITEM_TYPE,
                    type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, StructuredPostal.CITY, city) &&
                    cvCompareString(cv, StructuredPostal.STREET, street) &&
                    cvCompareString(cv, StructuredPostal.COUNTRY, country) &&
                    cvCompareString(cv, StructuredPostal.POSTCODE, code) &&
                    cvCompareString(cv, StructuredPostal.REGION, state)) {
                return;
            }
            builder.withValue(StructuredPostal.TYPE, type);
            builder.withValue(StructuredPostal.CITY, city);
            builder.withValue(StructuredPostal.STREET, street);
            builder.withValue(StructuredPostal.COUNTRY, country);
            builder.withValue(StructuredPostal.POSTCODE, code);
            builder.withValue(StructuredPostal.REGION, state);
            add(builder.build());
        }

        public void addIm(Entity entity, int type, String account) {
            SmartBuilder builder = createBuilder(entity, Im.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Im.DATA, account)) {
                return;
            }
            builder.withValue(Im.TYPE, type);
            builder.withValue(Im.DATA, account);
            add(builder.build());
        }

        public void addOrganization(Entity entity, int type, String company, String title,
                String department, String yomiCompanyName) {
            SmartBuilder builder = createBuilder(entity, Organization.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Organization.COMPANY, company) &&
                    cvCompareString(cv, Organization.PHONETIC_NAME, yomiCompanyName) &&
                    cvCompareString(cv, Organization.DEPARTMENT, department) &&
                    cvCompareString(cv, Organization.TITLE, title)) {
                return;
            }
            builder.withValue(Organization.TYPE, type);
            builder.withValue(Organization.COMPANY, company);
            builder.withValue(Organization.TITLE, title);
            builder.withValue(Organization.DEPARTMENT, department);
            builder.withValue(Organization.PHONETIC_NAME, yomiCompanyName);
            add(builder.build());
        }

        public void addNote(Entity entity, String note) {
            SmartBuilder builder = createBuilder(entity, Note.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (note != null) {
                note = note.replaceAll("\r\n", "\n");
            }
            if (cv != null && cvCompareString(cv, Note.NOTE, note)) {
                return;
            }
            builder.withValue(Note.NOTE, note);
            add(builder.build());
        }
    }

    /**
     * Generate the uri for the data row associated with this NamedContentValues object
     * @param ncv the NamedContentValues object
     * @return a uri that can be used to refer to this row
     */
    public Uri dataUriFromNamedContentValues(NamedContentValues ncv) {
        long id = ncv.values.getAsLong(RawContacts._ID);
        Uri dataUri = ContentUris.withAppendedId(ncv.uri, id);
        return dataUri;
    }

    @Override
    public void cleanup() {
        // Mark the changed contacts dirty = 0
        // TODO Put this in a single batch
        ContactOperations ops = new ContactOperations();
        for (Long id: mUpdatedIdList) {
            ops.add(ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id))
                    .withValue(RawContacts.DIRTY, 0).build());
        }

        ops.execute();
    }

    @Override
    public String getCollectionName() {
        return "Contacts";
    }

    private void sendEmail(Serializer s, ContentValues cv) throws IOException {
        // Get both parts of the email address (a newly created one in the UI won't have a name)
        String addr = cv.getAsString(Email.DATA);
        String name = cv.getAsString(Email.DISPLAY_NAME);
        // Don't crash if we don't have a name
        if (name == null) {
            name = "";
        }
        String value = null;
        // If there's no addr, just send an empty address (will delete it on the server)
        // Otherwise compose it from name and addr
        if (addr != null) {
            value = '\"' + name + "\" <" + addr + '>';
        }
        switch (cv.getAsInteger(Email.TYPE)) {
            case TYPE_EMAIL1:
                s.data(Tags.CONTACTS_EMAIL1_ADDRESS, value);
                break;
            case TYPE_EMAIL2:
                s.data(Tags.CONTACTS_EMAIL2_ADDRESS, value);
                break;
            case TYPE_EMAIL3:
                s.data(Tags.CONTACTS_EMAIL3_ADDRESS, value);
                break;
            default:
                break;
        }
    }

    private void sendIm(Serializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Im.DATA);
        if (value == null) return;
        switch (cv.getAsInteger(Im.TYPE)) {
            case TYPE_IM1:
                s.data(Tags.CONTACTS2_IM_ADDRESS, value);
                break;
            case TYPE_IM2:
                s.data(Tags.CONTACTS2_IM_ADDRESS_2, value);
                break;
            case TYPE_IM3:
                s.data(Tags.CONTACTS2_IM_ADDRESS_3, value);
                break;
            default:
                break;
        }
    }

    private void sendOnePostal(Serializer s, ContentValues cv, int[] fieldNames)
            throws IOException{
        if (cv.containsKey(StructuredPostal.CITY)) {
            s.data(fieldNames[0], cv.getAsString(StructuredPostal.CITY));
        }
        if (cv.containsKey(StructuredPostal.COUNTRY)) {
            s.data(fieldNames[1], cv.getAsString(StructuredPostal.COUNTRY));
        }
        if (cv.containsKey(StructuredPostal.POSTCODE)) {
            s.data(fieldNames[2], cv.getAsString(StructuredPostal.POSTCODE));
        }
        if (cv.containsKey(StructuredPostal.REGION)) {
            s.data(fieldNames[3], cv.getAsString(StructuredPostal.REGION));
        }
        if (cv.containsKey(StructuredPostal.STREET)) {
            s.data(fieldNames[4], cv.getAsString(StructuredPostal.STREET));
        }
    }

    private void sendStructuredPostal(Serializer s, ContentValues cv) throws IOException {
        switch (cv.getAsInteger(StructuredPostal.TYPE)) {
            case StructuredPostal.TYPE_HOME:
                sendOnePostal(s, cv, HOME_ADDRESS_TAGS);
                break;
            case StructuredPostal.TYPE_WORK:
                sendOnePostal(s, cv, WORK_ADDRESS_TAGS);
                break;
            case StructuredPostal.TYPE_OTHER:
                sendOnePostal(s, cv, OTHER_ADDRESS_TAGS);
                break;
            default:
                break;
        }
    }

    private void sendStructuredName(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(StructuredName.FAMILY_NAME)) {
            s.data(Tags.CONTACTS_LAST_NAME, cv.getAsString(StructuredName.FAMILY_NAME));
        }
        if (cv.containsKey(StructuredName.GIVEN_NAME)) {
            s.data(Tags.CONTACTS_FIRST_NAME, cv.getAsString(StructuredName.GIVEN_NAME));
        }
        if (cv.containsKey(StructuredName.MIDDLE_NAME)) {
            s.data(Tags.CONTACTS_MIDDLE_NAME, cv.getAsString(StructuredName.MIDDLE_NAME));
        }
        if (cv.containsKey(StructuredName.SUFFIX)) {
            s.data(Tags.CONTACTS_SUFFIX, cv.getAsString(StructuredName.SUFFIX));
        }
        if (cv.containsKey(StructuredName.PHONETIC_GIVEN_NAME)) {
            s.data(Tags.CONTACTS_YOMI_FIRST_NAME,
                    cv.getAsString(StructuredName.PHONETIC_GIVEN_NAME));
        }
        if (cv.containsKey(StructuredName.PHONETIC_FAMILY_NAME)) {
            s.data(Tags.CONTACTS_YOMI_LAST_NAME,
                    cv.getAsString(StructuredName.PHONETIC_FAMILY_NAME));
        }
        if (cv.containsKey(StructuredName.PREFIX)) {
            s.data(Tags.CONTACTS_TITLE, cv.getAsString(StructuredName.PREFIX));
        }
    }

    private void sendBusiness(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(EasBusiness.ACCOUNT_NAME)) {
            s.data(Tags.CONTACTS2_ACCOUNT_NAME, cv.getAsString(EasBusiness.ACCOUNT_NAME));
        }
        if (cv.containsKey(EasBusiness.CUSTOMER_ID)) {
            s.data(Tags.CONTACTS2_CUSTOMER_ID, cv.getAsString(EasBusiness.CUSTOMER_ID));
        }
        if (cv.containsKey(EasBusiness.GOVERNMENT_ID)) {
            s.data(Tags.CONTACTS2_GOVERNMENT_ID, cv.getAsString(EasBusiness.GOVERNMENT_ID));
        }
        if (cv.containsKey(EasBusiness.OFFICE_LOCATION)) {
            s.data(Tags.CONTACTS_OFFICE_LOCATION, cv.getAsString(EasBusiness.OFFICE_LOCATION));
        }
    }

    private void sendPersonal(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(EasPersonal.ANNIVERSARY)) {
            s.data(Tags.CONTACTS_ANNIVERSARY, cv.getAsString(EasPersonal.ANNIVERSARY));
        }
        if (cv.containsKey(EasPersonal.BIRTHDAY)) {
            s.data(Tags.CONTACTS_BIRTHDAY, cv.getAsString(EasPersonal.BIRTHDAY));
        }
        if (cv.containsKey(EasPersonal.FILE_AS)) {
            s.data(Tags.CONTACTS_FILE_AS, cv.getAsString(EasPersonal.FILE_AS));
        }
    }

    private void sendOrganization(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Organization.TITLE)) {
            s.data(Tags.CONTACTS_JOB_TITLE, cv.getAsString(Organization.TITLE));
        }
        if (cv.containsKey(Organization.COMPANY)) {
            s.data(Tags.CONTACTS_COMPANY_NAME, cv.getAsString(Organization.COMPANY));
        }
        if (cv.containsKey(Organization.DEPARTMENT)) {
            s.data(Tags.CONTACTS_DEPARTMENT, cv.getAsString(Organization.DEPARTMENT));
        }
    }

    private void sendNickname(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Nickname.NAME)) {
            s.data(Tags.CONTACTS2_NICKNAME, cv.getAsString(Nickname.NAME));
        }
    }

    private void sendWebpage(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Website.URL)) {
            s.data(Tags.CONTACTS_WEBPAGE, cv.getAsString(Website.URL));
        }
    }

    private void sendNote(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Note.NOTE)) {
            // EAS won't accept note data with raw newline characters
            String note = cv.getAsString(Note.NOTE).replaceAll("\n", "\r\n");
            // Format of upsync data depends on protocol version
            if (mService.mProtocolVersionDouble >= 12.0) {
                s.start(Tags.BASE_BODY);
                s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_TEXT).data(Tags.BASE_DATA, note);
                s.end();
            } else {
                s.data(Tags.CONTACTS_BODY, note);
            }
        }
    }

    private void sendChildren(Serializer s, ContentValues cv) throws IOException {
        boolean first = true;
        for (int i = 0; i < EasChildren.MAX_CHILDREN; i++) {
            String row = EasChildren.ROWS[i];
            if (cv.containsKey(row)) {
                if (first) {
                    s.start(Tags.CONTACTS_CHILDREN);
                    first = false;
                }
                s.data(Tags.CONTACTS_CHILD, cv.getAsString(row));
            }
        }
        if (!first) {
            s.end();
        }
    }

    private void sendPhone(Serializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Phone.NUMBER);
        switch (cv.getAsInteger(Phone.TYPE)) {
            case TYPE_WORK2:
                s.data(Tags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_WORK:
                s.data(Tags.CONTACTS_BUSINESS_TELEPHONE_NUMBER, value);
                break;
            case TYPE_MMS:
                s.data(Tags.CONTACTS2_MMS, value);
                break;
            case Phone.TYPE_FAX_WORK:
                s.data(Tags.CONTACTS_BUSINESS_FAX_NUMBER, value);
                break;
            case TYPE_COMPANY_MAIN:
                s.data(Tags.CONTACTS2_COMPANY_MAIN_PHONE, value);
                break;
            case Phone.TYPE_HOME:
                s.data(Tags.CONTACTS_HOME_TELEPHONE_NUMBER, value);
                break;
            case TYPE_HOME2:
                s.data(Tags.CONTACTS_HOME2_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_MOBILE:
                s.data(Tags.CONTACTS_MOBILE_TELEPHONE_NUMBER, value);
                break;
            case TYPE_CAR:
                s.data(Tags.CONTACTS_CAR_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_PAGER:
                s.data(Tags.CONTACTS_PAGER_NUMBER, value);
                break;
            case TYPE_RADIO:
                s.data(Tags.CONTACTS_RADIO_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_FAX_HOME:
                s.data(Tags.CONTACTS_HOME_FAX_NUMBER, value);
                break;
            case TYPE_EMAIL2:
                s.data(Tags.CONTACTS_EMAIL2_ADDRESS, value);
                break;
            case TYPE_EMAIL3:
                s.data(Tags.CONTACTS_EMAIL3_ADDRESS, value);
                break;
            default:
                break;
        }
    }

    private void sendRelation(Serializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Relation.DATA);
        switch (cv.getAsInteger(Relation.TYPE)) {
            case Relation.TYPE_ASSISTANT:
                s.data(Tags.CONTACTS_ASSISTANT_NAME, value);
                break;
            case Relation.TYPE_MANAGER:
                s.data(Tags.CONTACTS2_MANAGER_NAME, value);
                break;
            case Relation.TYPE_SPOUSE:
                s.data(Tags.CONTACTS_SPOUSE, value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        // First, let's find Contacts that have changed.
        ContentResolver cr = mService.mContentResolver;
        Uri uri = RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, mAccount.mEmailAddress)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE)
                .build();

        if (getSyncKey().equals("0")) {
            return false;
        }

        try {
            // Get them all atomically
            EntityIterator ei = cr.queryEntities(uri, RawContacts.DIRTY + "=1", null, null);
            ContentValues cidValues = new ContentValues();
            try {
                boolean first = true;
                while (ei.hasNext()) {
                    Entity entity = ei.next();
                    // For each of these entities, create the change commands
                    ContentValues entityValues = entity.getEntityValues();
                    String serverId = entityValues.getAsString(RawContacts.SOURCE_ID);
                    ArrayList<Integer> groupIds = new ArrayList<Integer>();
                    if (first) {
                        s.start(Tags.SYNC_COMMANDS);
                        first = false;
                    }
                    if (serverId == null) {
                        // This is a new contact; create a clientId
                        String clientId = "new_" + mMailbox.mId + '_' + System.currentTimeMillis();
                        userLog("Creating new contact with clientId: ", clientId);
                        s.start(Tags.SYNC_ADD).data(Tags.SYNC_CLIENT_ID, clientId);
                        // And save it in the raw contact
                        cidValues.put(ContactsContract.RawContacts.SYNC1, clientId);
                        cr.update(ContentUris.
                                withAppendedId(ContactsContract.RawContacts.CONTENT_URI,
                                        entityValues.getAsLong(ContactsContract.RawContacts._ID)),
                                        cidValues, null, null);
                    } else {
                        userLog("Upsync change to contact with serverId: " + serverId);
                        s.start(Tags.SYNC_CHANGE).data(Tags.SYNC_SERVER_ID, serverId);
                    }
                    s.start(Tags.SYNC_APPLICATION_DATA);
                    // Write out the data here
                    for (NamedContentValues ncv: entity.getSubValues()) {
                        ContentValues cv = ncv.values;
                        String mimeType = cv.getAsString(Data.MIMETYPE);
                        if (mimeType.equals(Email.CONTENT_ITEM_TYPE)) {
                            sendEmail(s, cv);
                        } else if (mimeType.equals(Nickname.CONTENT_ITEM_TYPE)) {
                            sendNickname(s, cv);
                        } else if (mimeType.equals(EasChildren.CONTENT_ITEM_TYPE)) {
                            sendChildren(s, cv);
                        } else if (mimeType.equals(EasBusiness.CONTENT_ITEM_TYPE)) {
                            sendBusiness(s, cv);
                        } else if (mimeType.equals(Website.CONTENT_ITEM_TYPE)) {
                            sendWebpage(s, cv);
                        } else if (mimeType.equals(EasPersonal.CONTENT_ITEM_TYPE)) {
                            sendPersonal(s, cv);
                        } else if (mimeType.equals(Phone.CONTENT_ITEM_TYPE)) {
                            sendPhone(s, cv);
                        } else if (mimeType.equals(Relation.CONTENT_ITEM_TYPE)) {
                            sendRelation(s, cv);
                        } else if (mimeType.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                            sendStructuredName(s, cv);
                        } else if (mimeType.equals(StructuredPostal.CONTENT_ITEM_TYPE)) {
                            sendStructuredPostal(s, cv);
                        } else if (mimeType.equals(Organization.CONTENT_ITEM_TYPE)) {
                            sendOrganization(s, cv);
                        } else if (mimeType.equals(Im.CONTENT_ITEM_TYPE)) {
                            sendIm(s, cv);
                        } else if (mimeType.equals(GroupMembership.CONTENT_ITEM_TYPE)) {
                            // We must gather these, and send them together (below)
                            groupIds.add(cv.getAsInteger(GroupMembership.GROUP_ROW_ID));
                        } else if (mimeType.equals(Note.CONTENT_ITEM_TYPE)) {
                            sendNote(s, cv);
                        } else if (mimeType.equals(Photo.CONTENT_ITEM_TYPE)) {
                            // For now, the user can change the photo, but the change won't be
                            // uploaded.
                        } else {
                            userLog("Contacts upsync, unknown data: ", mimeType);
                        }
                    }

                    // Now, we'll send up groups, if any
                    if (!groupIds.isEmpty()) {
                        boolean groupFirst = true;
                        for (int id: groupIds) {
                            // Since we get id's from the provider, we need to find their names
                            Cursor c = cr.query(ContentUris.withAppendedId(Groups.CONTENT_URI, id),
                                    GROUP_PROJECTION, null, null, null);
                            try {
                                // Presumably, this should always succeed, but ...
                                if (c.moveToFirst()) {
                                    if (groupFirst) {
                                        s.start(Tags.CONTACTS_CATEGORIES);
                                        groupFirst = false;
                                    }
                                    s.data(Tags.CONTACTS_CATEGORY, c.getString(0));
                                }
                            } finally {
                                c.close();
                            }
                        }
                        if (!groupFirst) {
                            s.end();
                        }
                    }
                    s.end().end(); // ApplicationData & Change
                    mUpdatedIdList.add(entityValues.getAsLong(RawContacts._ID));
                }
                if (!first) {
                    s.end(); // Commands
                }
            } finally {
                ei.close();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not read dirty contacts.");
        }

        return false;
    }
}
