/*
 * Sleuth Kit Data Model
 *
 * Copyright 2017-2021 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.datamodel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.sleuthkit.datamodel.Blackboard.BlackboardException;
import org.sleuthkit.datamodel.SleuthkitCase.CaseDbConnection;
import org.sleuthkit.datamodel.SleuthkitCase.CaseDbTransaction;
import static org.sleuthkit.datamodel.SleuthkitCase.closeConnection;
import static org.sleuthkit.datamodel.SleuthkitCase.closeResultSet;
import static org.sleuthkit.datamodel.SleuthkitCase.closeStatement;

/**
 * Provides an API to create Accounts and communications/relationships between
 * accounts.
 */
public final class CommunicationsManager {

	private static final Logger LOGGER = Logger.getLogger(CommunicationsManager.class.getName());
	private static final BlackboardArtifact.Type ACCOUNT_TYPE = new BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_ACCOUNT);
	private final SleuthkitCase db;

	private final Map<Account.Type, Integer> accountTypeToTypeIdMap
			= new ConcurrentHashMap<>();
	private final Map<String, Account.Type> typeNameToAccountTypeMap
			= new ConcurrentHashMap<>();

	// Artifact types that can represent a relationship between accounts. 
	private static final Set<Integer> RELATIONSHIP_ARTIFACT_TYPE_IDS = new HashSet<Integer>(Arrays.asList(
			BlackboardArtifact.ARTIFACT_TYPE.TSK_MESSAGE.getTypeID(),
			BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID(),
			BlackboardArtifact.ARTIFACT_TYPE.TSK_CONTACT.getTypeID(),
			BlackboardArtifact.ARTIFACT_TYPE.TSK_CALLLOG.getTypeID()
	));
	private static final String RELATIONSHIP_ARTIFACT_TYPE_IDS_CSV_STR = StringUtils.buildCSVString(RELATIONSHIP_ARTIFACT_TYPE_IDS);

	/**
	 * Construct a CommunicationsManager for the given SleuthkitCase.
	 *
	 * @param skCase The SleuthkitCase
	 *
	 * @throws TskCoreException if there is in error initializing the account
	 *                          types.
	 */
	CommunicationsManager(SleuthkitCase skCase) throws TskCoreException {
		this.db = skCase;
		initAccountTypes();
	}

	/**
	 * Make sure the predefined account types are in the account types table.
	 *
	 * @throws TskCoreException if there is an error reading the pre-existing
	 *                          account types from the db.
	 */
	private void initAccountTypes() throws TskCoreException {
		db.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement statement = connection.createStatement();) {
			// Read the table
			int count = readAccountTypes();
			if (0 == count) {
				// Table is empty, populate it with predefined types
				for (Account.Type type : Account.Type.PREDEFINED_ACCOUNT_TYPES) {
					try {
						statement.execute("INSERT INTO account_types (type_name, display_name) VALUES ( '" + type.getTypeName() + "', '" + type.getDisplayName() + "')"); //NON-NLS
					} catch (SQLException ex) {
						try (ResultSet resultSet = connection.executeQuery(statement, "SELECT COUNT(*) AS count FROM account_types WHERE type_name = '" + type.getTypeName() + "'")) { //NON-NLS
							resultSet.next();
							if (resultSet.getLong("count") == 0) {
								throw ex;
							}
						}
					}

					try (ResultSet rs2 = connection.executeQuery(statement, "SELECT account_type_id FROM account_types WHERE type_name = '" + type.getTypeName() + "'")) { //NON-NLS
						rs2.next();
						int typeID = rs2.getInt("account_type_id");

						Account.Type accountType = new Account.Type(type.getTypeName(), type.getDisplayName());
						this.accountTypeToTypeIdMap.put(accountType, typeID);
						this.typeNameToAccountTypeMap.put(type.getTypeName(), accountType);
					}
				}
			}
		} catch (SQLException ex) {
			LOGGER.log(Level.SEVERE, "Failed to add row to account_types", ex);
		} finally {
			db.releaseSingleUserCaseWriteLock();
		}
	}

	/**
	 * Reads in in the account types table and returns the number of account
	 * types read in.
	 *
	 * @return The number of account types read.
	 *
	 * @throws TskCoreException if there is a problem reading the account types.
	 */
	private int readAccountTypes() throws TskCoreException {
		CaseDbConnection connection = null;
		Statement statement = null;
		ResultSet resultSet = null;
		int count = 0;

		db.acquireSingleUserCaseReadLock();
		try {
			connection = db.getConnection();
			statement = connection.createStatement();

			// If the account_types table is already populated, say when opening a case,  then load it
			resultSet = connection.executeQuery(statement, "SELECT COUNT(*) AS count FROM account_types"); //NON-NLS
			resultSet.next();
			if (resultSet.getLong("count") > 0) {

				resultSet.close();
				resultSet = connection.executeQuery(statement, "SELECT * FROM account_types");
				while (resultSet.next()) {
					Account.Type accountType = new Account.Type(resultSet.getString("type_name"), resultSet.getString("display_name"));
					this.accountTypeToTypeIdMap.put(accountType, resultSet.getInt("account_type_id"));
					this.typeNameToAccountTypeMap.put(accountType.getTypeName(), accountType);
				}
				count = this.typeNameToAccountTypeMap.size();
			}

		} catch (SQLException ex) {
			throw new TskCoreException("Failed to read account_types", ex);
		} finally {
			closeResultSet(resultSet);
			closeStatement(statement);
			closeConnection(connection);
			db.releaseSingleUserCaseReadLock();
		}

		return count;
	}

	/**
	 * Gets the SleuthKit case.
	 *
	 * @return The SleuthKit case (case database) object.
	 */
	SleuthkitCase getSleuthkitCase() {
		return this.db;
	}

	/**
	 * Add a custom account type that is not already defined in Account.Type.
	 * Will not allow duplicates and will return existing type if the name is
	 * already defined.
	 *
	 * @param accountTypeName account type that must be unique
	 * @param displayName     account type display name
	 *
	 * @return Account.Type
	 *
	 * @throws TskCoreException if a critical error occurs within TSK core
	 */
	// NOTE: Full name given for Type for doxygen linking
	public org.sleuthkit.datamodel.Account.Type addAccountType(String accountTypeName, String displayName) throws TskCoreException {
		Account.Type accountType = new Account.Type(accountTypeName, displayName);

		// check if already in map
		if (this.accountTypeToTypeIdMap.containsKey(accountType)) {
			return accountType;
		}

		CaseDbTransaction trans = db.beginTransaction();
		Statement s = null;
		ResultSet rs = null;
		try {
			s = trans.getConnection().createStatement();
			rs = trans.getConnection().executeQuery(s, "SELECT * FROM account_types WHERE type_name = '" + accountTypeName + "'"); //NON-NLS
			if (!rs.next()) {
				rs.close();

				s.execute("INSERT INTO account_types (type_name, display_name) VALUES ( '" + accountTypeName + "', '" + displayName + "')"); //NON-NLS

				// Read back the typeID
				rs = trans.getConnection().executeQuery(s, "SELECT * FROM account_types WHERE type_name = '" + accountTypeName + "'"); //NON-NLS
				rs.next();

				int typeID = rs.getInt("account_type_id");
				accountType = new Account.Type(rs.getString("type_name"), rs.getString("display_name"));

				this.accountTypeToTypeIdMap.put(accountType, typeID);
				this.typeNameToAccountTypeMap.put(accountTypeName, accountType);

				trans.commit();

				return accountType;
			} else {
				int typeID = rs.getInt("account_type_id");

				accountType = new Account.Type(rs.getString("type_name"), rs.getString("display_name"));
				this.accountTypeToTypeIdMap.put(accountType, typeID);

				return accountType;
			}
		} catch (SQLException ex) {
			trans.rollback();
			throw new TskCoreException("Error adding account type", ex);
		} finally {
			closeResultSet(rs);
			closeStatement(s);
		}
	}

	/**
	 * Records that an account was used in a specific file. Behind the scenes,
	 * it will create a case-specific Account object if it does not already
	 * exist, and it will create the needed database entries (which currently
	 * includes making a TSK_ACCOUNT data artifact).
	 *
	 * @param accountType     The account type.
	 * @param accountUniqueID The unique account identifier (such as an email
	 *                        address).
	 * @param moduleName      The module creating the account.
	 * @param sourceFile      The source file the account was found in.
	 * @param ingestJobId     The ingest job in which the analysis that found
	 *                        the account was performed, may be null.
	 *
	 * @return	An AccountFileInstance object.
	 *
	 * @throws TskCoreException          The exception is thrown if there is an
	 *                                   issue updating the case database.
	 * @throws InvalidAccountIDException The exception is thrown if the account
	 *                                   ID is not valid for the account type.
	 */
	// NOTE: Full name given for Type for doxygen linking
	public AccountFileInstance createAccountFileInstance(org.sleuthkit.datamodel.Account.Type accountType, String accountUniqueID, String moduleName, Content sourceFile, Long ingestJobId) throws TskCoreException, InvalidAccountIDException {

		// make or get the Account (unique at the case-level)
		Account account = getOrCreateAccount(accountType, normalizeAccountID(accountType, accountUniqueID));

		/*
		 * make or get the artifact. Will not create one if it already exists
		 * for the sourceFile. Such as an email PST that has the same email
		 * address multiple times. Only one artifact is created for each email
		 * message in that PST.
		 */
		BlackboardArtifact accountArtifact = getOrCreateAccountFileInstanceArtifact(accountType, normalizeAccountID(accountType, accountUniqueID), moduleName, sourceFile, ingestJobId);

		// The account instance map was unused so we have removed it from the database, 
		// but we expect we may need it so I am preserving this method comment and usage here.
		// add a row to Accounts to Instances mapping table
		// @@@ BC: Seems like we should only do this if we had to create the artifact. 
		// But, it will probably fail to create a new one based on unique constraints. 
		// addAccountFileInstanceMapping(account.getAccountID(), accountArtifact.getArtifactID());
		return new AccountFileInstance(accountArtifact, account);
	}

	/**
	 * Records that an account was used in a specific file. Behind the scenes,
	 * it will create a case-specific Account object if it does not already
	 * exist, and it will create the needed database entries (which currently
	 * includes making a TSK_ACCOUNT data artifact).
	 *
	 * @param accountType     The account type.
	 * @param accountUniqueID The unique account identifier (such as an email
	 *                        address).
	 * @param moduleName      The module creating the account.
	 * @param sourceFile      The source file the account was found in.
	 *
	 * @return	An AccountFileInstance object.
	 *
	 * @throws TskCoreException          The exception is thrown if there is an
	 *                                   issue updating the case database.
	 * @throws InvalidAccountIDException The exception is thrown if the account
	 *                                   ID is not valid for the account type.
	 * @deprecated Use
	 * createAccountFileInstance(org.sleuthkit.datamodel.Account.Type
	 * accountType, String accountUniqueID, String moduleName, Content
	 * sourceFile, Long ingestJobId) instead.
	 */
	@Deprecated
	// NOTE: Full name given for Type for doxygen linking
	public AccountFileInstance createAccountFileInstance(org.sleuthkit.datamodel.Account.Type accountType, String accountUniqueID, String moduleName, Content sourceFile) throws TskCoreException, InvalidAccountIDException {
		return createAccountFileInstance(accountType, accountUniqueID, moduleName, sourceFile, null);
	}

	/**
	 * Get the Account with the given account type and account ID.
	 *
	 * @param accountType     account type
	 * @param accountUniqueID unique account identifier (such as an email
	 *                        address)
	 *
	 * @return Account, returns NULL is no matching account found
	 *
	 * @throws TskCoreException          If a critical error occurs within TSK
	 *                                   core.
	 * @throws InvalidAccountIDException If the account identifier is not valid.
	 */
	// NOTE: Full name given for Type for doxygen linking
	public Account getAccount(org.sleuthkit.datamodel.Account.Type accountType, String accountUniqueID) throws TskCoreException, InvalidAccountIDException {
		Account account = null;
		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, "SELECT * FROM accounts WHERE account_type_id = " + getAccountTypeId(accountType)
						+ " AND account_unique_identifier = '" + normalizeAccountID(accountType, accountUniqueID) + "'");) { //NON-NLS

			if (rs.next()) {
				account = new Account(rs.getInt("account_id"), accountType,
						rs.getString("account_unique_identifier"));
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account type id", ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}

		return account;
	}

	/**
	 * Adds relationships between the sender and each of the recipient account
	 * instances and between all recipient account instances. All account
	 * instances must be from the same data source.
	 *
	 * @param sender           Sender account, may be null.
	 * @param recipients       List of recipients, may be empty.
	 * @param sourceArtifact   Artifact that relationships were derived from.
	 * @param relationshipType The type of relationships to be created.
	 * @param dateTime         Date of communications/relationship, as epoch
	 *                         seconds.
	 *
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 * @throws org.sleuthkit.datamodel.TskDataException If the all the accounts
	 *                                                  and the relationship are
	 *                                                  not from the same data
	 *                                                  source, or if the
	 *                                                  sourceArtifact and
	 *                                                  relationshipType are not
	 *                                                  compatible.
	 */
	// NOTE: Full name given for Type for doxygen linking
	public void addRelationships(AccountFileInstance sender, List<AccountFileInstance> recipients,
			BlackboardArtifact sourceArtifact, org.sleuthkit.datamodel.Relationship.Type relationshipType, long dateTime) throws TskCoreException, TskDataException {

		if (sourceArtifact.getDataSourceObjectID() == null) {
			throw new TskDataException("Source Artifact does not have a valid data source.");
		}

		if (relationshipType.isCreatableFrom(sourceArtifact) == false) {
			throw new TskDataException("Can not make a " + relationshipType.getDisplayName()
					+ " relationship from a" + sourceArtifact.getDisplayName());
		}

		/*
		 * Enforce that all accounts and the relationship between them are from
		 * the same 'source'. This is required for the queries to work
		 * correctly.
		 */
		// Currently we do not save the direction of communication
		List<Long> accountIDs = new ArrayList<>();

		if (null != sender) {
			accountIDs.add(sender.getAccount().getAccountID());
			if (!sender.getDataSourceObjectID().equals(sourceArtifact.getDataSourceObjectID())) {
				throw new TskDataException("Sender and relationship are from different data sources :"
						+ "Sender source ID" + sender.getDataSourceObjectID() + " != relationship source ID" + sourceArtifact.getDataSourceObjectID());
			}
		}

		for (AccountFileInstance recipient : recipients) {
			accountIDs.add(recipient.getAccount().getAccountID());
			if (!recipient.getDataSourceObjectID().equals(sourceArtifact.getDataSourceObjectID())) {
				throw new TskDataException("Recipient and relationship are from different data sources :"
						+ "Recipient source ID" + recipient.getDataSourceObjectID() + " != relationship source ID" + sourceArtifact.getDataSourceObjectID());
			}
		}

		// Set up the query for the prepared statement
		String query = "INTO account_relationships (account1_id, account2_id, relationship_source_obj_id, date_time, relationship_type, data_source_obj_id  ) "
				+ "VALUES (?,?,?,?,?,?)";
		switch (db.getDatabaseType()) {
			case POSTGRESQL:
				query = "INSERT " + query + " ON CONFLICT DO NOTHING";
				break;
			case SQLITE:
				query = "INSERT OR IGNORE " + query;
				break;
			default:
				throw new TskCoreException("Unknown DB Type: " + db.getDatabaseType().name());
		}

		CaseDbTransaction trans = db.beginTransaction();
		try {
			SleuthkitCase.CaseDbConnection connection = trans.getConnection();
			PreparedStatement preparedStatement = connection.getPreparedStatement(query, Statement.NO_GENERATED_KEYS);

			for (int i = 0; i < accountIDs.size(); i++) {
				for (int j = i + 1; j < accountIDs.size(); j++) {
					long account1_id = accountIDs.get(i);
					long account2_id = accountIDs.get(j);

					preparedStatement.clearParameters();
					preparedStatement.setLong(1, account1_id);
					preparedStatement.setLong(2, account2_id);
					preparedStatement.setLong(3, sourceArtifact.getId());
					if (dateTime > 0) {
						preparedStatement.setLong(4, dateTime);
					} else {
						preparedStatement.setNull(4, java.sql.Types.BIGINT);
					}
					preparedStatement.setInt(5, relationshipType.getTypeID());
					preparedStatement.setLong(6, sourceArtifact.getDataSourceObjectID());

					connection.executeUpdate(preparedStatement);
				}
			}
			trans.commit();
		} catch (SQLException ex) {
			trans.rollback();
			throw new TskCoreException("Error adding accounts relationship", ex);
		}
	}

	/**
	 * Get the Account for the given account type and account ID. Create an a
	 * new account if one doesn't exist
	 *
	 * @param accountType     account type
	 * @param accountUniqueID unique account identifier
	 *
	 * @return A matching account, either existing or newly created.
	 *
	 * @throws TskCoreException          exception thrown if a critical error
	 *                                   occurs within TSK core
	 * @throws InvalidAccountIDException If the account identifier is not valid.
	 *
	 */
	private Account getOrCreateAccount(Account.Type accountType, String accountUniqueID) throws TskCoreException, InvalidAccountIDException {
		Account account = getAccount(accountType, accountUniqueID);
		if (null == account) {
			String query = " INTO accounts (account_type_id, account_unique_identifier) "
					+ "VALUES ( " + getAccountTypeId(accountType) + ", '"
					+ normalizeAccountID(accountType, accountUniqueID) + "'" + ")";
			switch (db.getDatabaseType()) {
				case POSTGRESQL:
					query = "INSERT " + query + " ON CONFLICT DO NOTHING"; //NON-NLS
					break;
				case SQLITE:
					query = "INSERT OR IGNORE " + query;
					break;
				default:
					throw new TskCoreException("Unknown DB Type: " + db.getDatabaseType().name());
			}

			CaseDbTransaction trans = db.beginTransaction();
			Statement s = null;
			ResultSet rs = null;
			try {
				s = trans.getConnection().createStatement();

				s.execute(query);

				trans.commit();
				account = getAccount(accountType, accountUniqueID);
			} catch (SQLException ex) {
				trans.rollback();
				throw new TskCoreException("Error adding an account", ex);
			} finally {
				closeResultSet(rs);
				closeStatement(s);
			}
		}

		return account;
	}

	/**
	 * Gets or creates an account artifact for an instance of an account found
	 * in a file.
	 *
	 * @param accountType     The account type of the account instance.
	 * @param accountUniqueID The account ID of the account instance, should be
	 *                        unique for the account type (e.g., an email
	 *                        address for an email account).
	 * @param moduleName      The name of the module that found the account
	 *                        instance.
	 * @param sourceFile      The file in which the account instance was found.
	 * @param ingestJobId     The ingest job in which the analysis that found
	 *                        the account was performed, may be null.
	 *
	 * @return The account artifact.
	 *
	 * @throws TskCoreException If there is an error querying or updating the
	 *                          case database.
	 */
	private BlackboardArtifact getOrCreateAccountFileInstanceArtifact(Account.Type accountType, String accountUniqueID, String moduleName, Content sourceFile, Long ingestJobId) throws TskCoreException {
		if (sourceFile == null) {
			throw new TskCoreException("Source file not provided.");
		}

		BlackboardArtifact accountArtifact = getAccountFileInstanceArtifact(accountType, accountUniqueID, sourceFile);
		if (accountArtifact == null) {
			List<BlackboardAttribute> attributes = Arrays.asList(
					new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ACCOUNT_TYPE, moduleName, accountType.getTypeName()),
					new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ID, moduleName, accountUniqueID)
			);

			accountArtifact = sourceFile.newDataArtifact(ACCOUNT_TYPE, attributes);

			try {
				db.getBlackboard().postArtifact(accountArtifact, moduleName, ingestJobId);
			} catch (BlackboardException ex) {
				LOGGER.log(Level.SEVERE, String.format("Error posting new account artifact to the blackboard (object ID = %d)", accountArtifact.getId()), ex);
			}
		}
		return accountArtifact;
	}

	/**
	 * Get the blackboard artifact for the given account type, account ID, and
	 * source file
	 *
	 * @param accountType     account type
	 * @param accountUniqueID Unique account ID (such as email address)
	 * @param sourceFile		    Source file (for the artifact)
	 *
	 * @return blackboard artifact, returns NULL is no matching account found
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	private BlackboardArtifact getAccountFileInstanceArtifact(Account.Type accountType, String accountUniqueID, Content sourceFile) throws TskCoreException {
		BlackboardArtifact accountArtifact = null;

		String queryStr = "SELECT artifacts.artifact_id AS artifact_id,"
				+ " artifacts.obj_id AS obj_id,"
				+ " artifacts.artifact_obj_id AS artifact_obj_id,"
				+ " artifacts.data_source_obj_id AS data_source_obj_id,"
				+ " artifacts.artifact_type_id AS artifact_type_id,"
				+ " artifacts.review_status_id AS review_status_id,"
				+ " tsk_data_artifacts.os_account_obj_id AS os_account_obj_id"
				+ " FROM blackboard_artifacts AS artifacts"
				+ "	JOIN blackboard_attributes AS attr_account_type"
				+ "		ON artifacts.artifact_id = attr_account_type.artifact_id"
				+ " JOIN blackboard_attributes AS attr_account_id"
				+ "		ON artifacts.artifact_id = attr_account_id.artifact_id"
				+ "		AND attr_account_id.attribute_type_id = " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ID.getTypeID()
				+ "	    AND attr_account_id.value_text = '" + accountUniqueID + "'"
				+ " LEFT JOIN tsk_data_artifacts ON tsk_data_artifacts.artifact_obj_id = artifacts.artifact_obj_id"
				+ " WHERE artifacts.artifact_type_id = " + BlackboardArtifact.ARTIFACT_TYPE.TSK_ACCOUNT.getTypeID()
				+ " AND attr_account_type.attribute_type_id = " + BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ACCOUNT_TYPE.getTypeID()
				+ " AND attr_account_type.value_text = '" + accountType.getTypeName() + "'"
				+ " AND artifacts.obj_id = " + sourceFile.getId(); //NON-NLS

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryStr);) { //NON-NLS
			if (rs.next()) {
				BlackboardArtifact.Type bbartType = db.getBlackboard().getArtifactType(rs.getInt("artifact_type_id"));

				accountArtifact = new DataArtifact(db, rs.getLong("artifact_id"), rs.getLong("obj_id"), rs.getLong("artifact_obj_id"),
						rs.getObject("data_source_obj_id") != null ? rs.getLong("data_source_obj_id") : null,
						bbartType.getTypeID(), bbartType.getTypeName(), bbartType.getDisplayName(),
						BlackboardArtifact.ReviewStatus.withID(rs.getInt("review_status_id")), rs.getLong("os_account_obj_id"), false);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account", ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}

		return accountArtifact;
	}

	/**
	 * Get the Account.Type for the give type name.
	 *
	 * @param accountTypeName An account type name.
	 *
	 * @return An Account.Type or null if the account type does not exist.
	 *
	 * @throws TskCoreException If an error occurs accessing the case database.
	 */
	// NOTE: Full name given for Type for doxygen linking
	public org.sleuthkit.datamodel.Account.Type getAccountType(String accountTypeName) throws TskCoreException {
		if (this.typeNameToAccountTypeMap.containsKey(accountTypeName)) {
			return this.typeNameToAccountTypeMap.get(accountTypeName);
		}

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, "SELECT account_type_id, type_name, display_name FROM account_types WHERE type_name = '" + accountTypeName + "'");) { //NON-NLS
			Account.Type accountType = null;
			if (rs.next()) {
				accountType = new Account.Type(accountTypeName, rs.getString("display_name"));
				this.accountTypeToTypeIdMap.put(accountType, rs.getInt("account_type_id"));
				this.typeNameToAccountTypeMap.put(accountTypeName, accountType);
			}
			return accountType;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account type id", ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Returns a list of AccountDeviceInstances that have at least one
	 * relationship that meets the criteria listed in the filters.
	 *
	 * Applicable filters: DeviceFilter, AccountTypeFilter, DateRangeFilter,
	 * RelationshipTypeFilter, MostRecentFilter
	 *
	 * @param filter filters to apply
	 *
	 * @return list of AccountDeviceInstances
	 *
	 * @throws TskCoreException exception thrown if a critical error occurs
	 *                          within TSK core
	 */
	public List<AccountDeviceInstance> getAccountDeviceInstancesWithRelationships(CommunicationsFilter filter) throws TskCoreException {

		//set up applicable filters 
		Set<String> applicableInnerQueryFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.DateRangeFilter.class.getName(),
				CommunicationsFilter.DeviceFilter.class.getName(),
				CommunicationsFilter.RelationshipTypeFilter.class.getName()
		));
		String relationshipFilterSQL = getCommunicationsFilterSQL(filter, applicableInnerQueryFilters);

		String relationshipLimitSQL = getMostRecentFilterLimitSQL(filter);

		String relTblfilterQuery
				= "SELECT * "
				+ "FROM account_relationships as relationships"
				+ (relationshipFilterSQL.isEmpty() ? "" : " WHERE " + relationshipFilterSQL)
				+ (relationshipLimitSQL.isEmpty() ? "" : relationshipLimitSQL);

		String uniqueAccountQueryTemplate
				= " SELECT %1$1s as account_id,"
				+ " data_source_obj_id"
				+ " FROM ( " + relTblfilterQuery + ")AS %2$s";

		String relationshipTableFilterQuery1 = String.format(uniqueAccountQueryTemplate, "account1_id", "union_query_1");
		String relationshipTableFilterQuery2 = String.format(uniqueAccountQueryTemplate, "account2_id", "union_query_2");

		//this query groups by account_id and data_source_obj_id across both innerQueries
		String uniqueAccountQuery
				= "SELECT DISTINCT account_id, data_source_obj_id"
				+ " FROM ( " + relationshipTableFilterQuery1 + " UNION " + relationshipTableFilterQuery2 + " ) AS inner_union"
				+ " GROUP BY account_id, data_source_obj_id";

		// set up applicable filters
		Set<String> applicableFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.AccountTypeFilter.class.getName()
		));

		String accountTypeFilterSQL = getCommunicationsFilterSQL(filter, applicableFilters);

		String queryStr
				= //account info
				" accounts.account_id AS account_id,"
				+ " accounts.account_unique_identifier AS account_unique_identifier,"
				//account type info
				+ " account_types.type_name AS type_name,"
				//Account device instance info
				+ " data_source_info.device_id AS device_id"
				+ " FROM ( " + uniqueAccountQuery + " ) AS account_device_instances"
				+ " JOIN accounts AS accounts"
				+ "		ON accounts.account_id = account_device_instances.account_id"
				+ " JOIN account_types AS account_types"
				+ "		ON accounts.account_type_id = account_types.account_type_id"
				+ " JOIN data_source_info AS data_source_info"
				+ "		ON account_device_instances.data_source_obj_id = data_source_info.obj_id"
				+ (accountTypeFilterSQL.isEmpty() ? "" : " WHERE " + accountTypeFilterSQL);

		switch (db.getDatabaseType()) {
			case POSTGRESQL:
				queryStr = "SELECT DISTINCT ON ( accounts.account_id, data_source_info.device_id) " + queryStr;
				break;
			case SQLITE:
				queryStr = "SELECT " + queryStr + " GROUP BY accounts.account_id, data_source_info.device_id";
				break;
			default:
				throw new TskCoreException("Unknown DB Type: " + db.getDatabaseType().name());
		}

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryStr);) { //NON-NLS
			ArrayList<AccountDeviceInstance> accountDeviceInstances = new ArrayList<AccountDeviceInstance>();
			while (rs.next()) {
				long account_id = rs.getLong("account_id");
				String deviceID = rs.getString("device_id");
				final String type_name = rs.getString("type_name");
				final String account_unique_identifier = rs.getString("account_unique_identifier");

				Account.Type accountType = typeNameToAccountTypeMap.get(type_name);
				Account account = new Account(account_id, accountType, account_unique_identifier);
				accountDeviceInstances.add(new AccountDeviceInstance(account, deviceID));
			}

			return accountDeviceInstances;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account device instances. " + ex.getMessage(), ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Get the number of relationships between all pairs of accounts in the
	 * given set. For each pair of accounts <a2,a1> == <a1,a2>, find the number
	 * of relationships between those two accounts that pass the given filter,.
	 *
	 * Applicable filters: DeviceFilter, DateRangeFilter, RelationshipTypeFilter
	 *
	 * @param accounts The set of accounts to count the relationships (pairwise)
	 *                 between.
	 * @param filter   The filter that relationships must pass to be included in
	 *                 the count.
	 *
	 * @return The number of relationships (that pass the filter) between each
	 *         pair of accounts, organized in a map where the key is an
	 *         unordered pair of account ids, and the value is the number of
	 *         relationships.
	 *
	 * @throws TskCoreException if there is a problem querying the DB.
	 */
	public Map<AccountPair, Long> getRelationshipCountsPairwise(Set<AccountDeviceInstance> accounts, CommunicationsFilter filter) throws TskCoreException {

		Set<Long> accountIDs = new HashSet<Long>();
		Set<String> accountDeviceIDs = new HashSet<String>();
		for (AccountDeviceInstance adi : accounts) {
			accountIDs.add(adi.getAccount().getAccountID());
			accountDeviceIDs.add("'" + adi.getDeviceId() + "'");
		}
		//set up applicable filters 
		Set<String> applicableFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.DateRangeFilter.class.getName(),
				CommunicationsFilter.DeviceFilter.class.getName(),
				CommunicationsFilter.RelationshipTypeFilter.class.getName()
		));

		String accountIDsCSL = StringUtils.buildCSVString(accountIDs);
		String accountDeviceIDsCSL = StringUtils.buildCSVString(accountDeviceIDs);
		String filterSQL = getCommunicationsFilterSQL(filter, applicableFilters);

		final String queryString
				= " SELECT  count(DISTINCT relationships.relationship_source_obj_id) AS count," //realtionship count
				+ "		data_source_info.device_id AS device_id,"
				//account 1 info
				+ "		accounts1.account_id AS account1_id,"
				+ "		accounts1.account_unique_identifier AS account1_unique_identifier,"
				+ "		account_types1.type_name AS type_name1,"
				+ "		account_types1.display_name AS display_name1,"
				//account 2 info
				+ "		accounts2.account_id AS account2_id,"
				+ "		accounts2.account_unique_identifier AS account2_unique_identifier,"
				+ "		account_types2.type_name AS type_name2,"
				+ "		account_types2.display_name AS display_name2"
				+ " FROM account_relationships AS relationships"
				+ "	JOIN data_source_info AS data_source_info"
				+ "		ON relationships.data_source_obj_id = data_source_info.obj_id "
				//account1 aliases
				+ "	JOIN accounts AS accounts1	 "
				+ "		ON accounts1.account_id = relationships.account1_id"
				+ "	JOIN account_types AS account_types1"
				+ "		ON accounts1.account_type_id = account_types1.account_type_id"
				//account2 aliases
				+ "	JOIN accounts AS accounts2	 "
				+ "		ON accounts2.account_id = relationships.account2_id"
				+ "	JOIN account_types AS account_types2"
				+ "		ON accounts2.account_type_id = account_types2.account_type_id"
				+ " WHERE (( relationships.account1_id IN (" + accountIDsCSL + ")) "
				+ "		AND ( relationships.account2_id IN ( " + accountIDsCSL + " ))"
				+ "		AND ( data_source_info.device_id IN (" + accountDeviceIDsCSL + "))) "
				+ (filterSQL.isEmpty() ? "" : " AND " + filterSQL)
				+ "  GROUP BY data_source_info.device_id, "
				+ "		accounts1.account_id, "
				+ "		account_types1.type_name, "
				+ "		account_types1.display_name, "
				+ "		accounts2.account_id, "
				+ "		account_types2.type_name, "
				+ "		account_types2.display_name";

		Map<AccountPair, Long> results = new HashMap<AccountPair, Long>();

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryString);) { //NON-NLS

			while (rs.next()) {
				//make account 1
				Account.Type type1 = new Account.Type(rs.getString("type_name1"), rs.getString("display_name1"));
				AccountDeviceInstance adi1 = new AccountDeviceInstance(new Account(rs.getLong("account1_id"), type1,
						rs.getString("account1_unique_identifier")),
						rs.getString("device_id"));

				//make account 2
				Account.Type type2 = new Account.Type(rs.getString("type_name2"), rs.getString("display_name2"));
				AccountDeviceInstance adi2 = new AccountDeviceInstance(new Account(rs.getLong("account2_id"), type2,
						rs.getString("account2_unique_identifier")),
						rs.getString("device_id"));

				AccountPair relationshipKey = new AccountPair(adi1, adi2);
				long count = rs.getLong("count");

				//merge counts for relationships that have the accounts flipped.
				Long oldCount = results.get(relationshipKey);
				if (oldCount != null) {
					count += oldCount;
				}
				results.put(relationshipKey, count);
			}
			return results;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships between accounts. " + ex.getMessage(), ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Get the number of unique relationship sources (such as EMAIL artifacts)
	 * associated with an account on a given device (AccountDeviceInstance) that
	 * meet the filter criteria.
	 *
	 * Applicable filters: RelationshipTypeFilter, DateRangeFilter
	 *
	 * @param accountDeviceInstance Account of interest
	 * @param filter                Filters to apply.
	 *
	 * @return number of account relationships found for this account.
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 *
	 */
	public long getRelationshipSourcesCount(AccountDeviceInstance accountDeviceInstance, CommunicationsFilter filter) throws TskCoreException {

		long account_id = accountDeviceInstance.getAccount().getAccountID();

		// Get the list of Data source objects IDs correpsonding to this DeviceID.
		String datasourceObjIdsCSV = StringUtils.buildCSVString(
				db.getDataSourceObjIds(accountDeviceInstance.getDeviceId()));

		// set up applicable filters
		Set<String> applicableFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.RelationshipTypeFilter.class.getName(),
				CommunicationsFilter.DateRangeFilter.class.getName()
		));
		String filterSQL = getCommunicationsFilterSQL(filter, applicableFilters);

		String innerQuery = " account_relationships AS relationships";
		String limitStr = getMostRecentFilterLimitSQL(filter);

		if (!limitStr.isEmpty()) {
			innerQuery = "(SELECT * FROM account_relationships as relationships " + limitStr + ") as relationships";
		}

		String queryStr
				= "SELECT count(DISTINCT relationships.relationship_source_obj_id) as count "
				+ "	FROM" + innerQuery
				+ " WHERE relationships.data_source_obj_id IN ( " + datasourceObjIdsCSV + " )"
				+ " AND ( relationships.account1_id = " + account_id
				+ "      OR  relationships.account2_id = " + account_id + " )"
				+ (filterSQL.isEmpty() ? "" : " AND " + filterSQL);

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryStr);) { //NON-NLS
			rs.next();
			return (rs.getLong("count"));
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships count for account device instance. " + ex.getMessage(), ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Get the unique relationship sources (such as EMAIL artifacts) associated
	 * with accounts on specific devices (AccountDeviceInstance) that meet the
	 * filter criteria.
	 *
	 * Applicable filters: RelationshipTypeFilter, DateRangeFilter,
	 * MostRecentFilter
	 *
	 * @param accountDeviceInstanceList set of account device instances for
	 *                                  which to get the relationship sources.
	 * @param filter                    Filters to apply.
	 *
	 * @return relationship sources found for given account(s).
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public Set<Content> getRelationshipSources(Set<AccountDeviceInstance> accountDeviceInstanceList, CommunicationsFilter filter) throws TskCoreException {

		if (accountDeviceInstanceList.isEmpty()) {
			//log this?
			return Collections.emptySet();
		}

		Map<Long, Set<Long>> accountIdToDatasourceObjIdMap = new HashMap<>();
		for (AccountDeviceInstance accountDeviceInstance : accountDeviceInstanceList) {
			long accountID = accountDeviceInstance.getAccount().getAccountID();
			List<Long> dataSourceObjIds = db.getDataSourceObjIds(accountDeviceInstance.getDeviceId());

			if (accountIdToDatasourceObjIdMap.containsKey(accountID)) {
				accountIdToDatasourceObjIdMap.get(accountID).addAll(dataSourceObjIds);
			} else {
				accountIdToDatasourceObjIdMap.put(accountID, new HashSet<>(dataSourceObjIds));
			}
		}

		List<String> adiSQLClauses = new ArrayList<>();
		for (Map.Entry<Long, Set<Long>> entry : accountIdToDatasourceObjIdMap.entrySet()) {
			final Long accountID = entry.getKey();
			String datasourceObjIdsCSV = StringUtils.buildCSVString(entry.getValue());

			adiSQLClauses.add(
					"( ( relationships.data_source_obj_id IN ( " + datasourceObjIdsCSV + " ) )"
					+ " AND ( relationships.account1_id = " + accountID
					+ " OR relationships.account2_id = " + accountID + " ) )"
			);
		}
		String adiSQLClause = StringUtils.joinAsStrings(adiSQLClauses, " OR ");

		// set up applicable filters
		Set<String> applicableFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.RelationshipTypeFilter.class
						.getName(),
				CommunicationsFilter.DateRangeFilter.class
						.getName()
		));
		String filterSQL = getCommunicationsFilterSQL(filter, applicableFilters);

		String limitQuery = " account_relationships AS relationships";
		String limitStr = getMostRecentFilterLimitSQL(filter);
		if (!limitStr.isEmpty()) {
			limitQuery = "(SELECT * FROM account_relationships as relationships " + limitStr + ") as relationships";
		}

		String queryStr
				= "SELECT DISTINCT artifacts.artifact_id AS artifact_id,"
				+ " artifacts.obj_id AS obj_id,"
				+ " artifacts.artifact_obj_id AS artifact_obj_id,"
				+ " artifacts.data_source_obj_id AS data_source_obj_id, "
				+ " artifacts.artifact_type_id AS artifact_type_id, "
				+ " artifacts.review_status_id AS review_status_id,"
				+ " tsk_data_artifacts.os_account_obj_id as os_account_obj_id"
				+ " FROM blackboard_artifacts as artifacts"
				+ " JOIN " + limitQuery
				+ "	ON artifacts.artifact_obj_id = relationships.relationship_source_obj_id"
				+ " LEFT JOIN tsk_data_artifacts ON artifacts.artifact_obj_id = tsk_data_artifacts.artifact_obj_id"
				// append sql to restrict search to specified account device instances 
				+ " WHERE (" + adiSQLClause + " )"
				// plus other filters
				+ (filterSQL.isEmpty() ? "" : " AND (" + filterSQL + " )");

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryStr);) { //NON-NLS
			Set<Content> relationshipSources = new HashSet<>();
			relationshipSources.addAll(getDataArtifactsFromResult(rs));
			return relationshipSources;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships for account. " + ex.getMessage(), ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Get a set of AccountDeviceInstances that have relationships with the
	 * given AccountDeviceInstance and meet the criteria of the given filter.
	 *
	 * Applicable filters: DeviceFilter, DateRangeFilter, RelationshipTypeFilter
	 *
	 * @param accountDeviceInstance The account device instance.
	 * @param filter                The filters to apply.
	 *
	 * @return A set of AccountDeviceInstances that have relationships with the
	 *         given AccountDeviceInstance and meet the criteria of the given
	 *         filter.
	 *
	 * @throws TskCoreException if there is a serious error executing he query.
	 */
	public List<AccountDeviceInstance> getRelatedAccountDeviceInstances(AccountDeviceInstance accountDeviceInstance, CommunicationsFilter filter) throws TskCoreException {
		final List<Long> dataSourceObjIds
				= getSleuthkitCase().getDataSourceObjIds(accountDeviceInstance.getDeviceId());

		//set up applicable filters 
		Set<String> applicableInnerQueryFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.DateRangeFilter.class.getName(),
				CommunicationsFilter.DeviceFilter.class.getName(),
				CommunicationsFilter.RelationshipTypeFilter.class.getName()
		));

		String innerQueryfilterSQL = getCommunicationsFilterSQL(filter, applicableInnerQueryFilters);

		String innerQueryTemplate
				= " SELECT %1$1s as account_id,"
				+ "		  data_source_obj_id"
				+ " FROM account_relationships as relationships"
				+ " WHERE %2$1s = " + accountDeviceInstance.getAccount().getAccountID() + ""
				+ " AND data_source_obj_id IN (" + StringUtils.buildCSVString(dataSourceObjIds) + ")"
				+ (innerQueryfilterSQL.isEmpty() ? "" : " AND " + innerQueryfilterSQL);

		String innerQuery1 = String.format(innerQueryTemplate, "account1_id", "account2_id");
		String innerQuery2 = String.format(innerQueryTemplate, "account2_id", "account1_id");

		//this query groups by account_id and data_source_obj_id across both innerQueries
		String combinedInnerQuery
				= "SELECT account_id, data_source_obj_id "
				+ " FROM ( " + innerQuery1 + " UNION " + innerQuery2 + " ) AS  inner_union"
				+ " GROUP BY account_id, data_source_obj_id";

		// set up applicable filters
		Set<String> applicableFilters = new HashSet<String>(Arrays.asList(
				CommunicationsFilter.AccountTypeFilter.class.getName()
		));

		String filterSQL = getCommunicationsFilterSQL(filter, applicableFilters);

		String queryStr
				= //account info
				" accounts.account_id AS account_id,"
				+ " accounts.account_unique_identifier AS account_unique_identifier,"
				//account type info
				+ " account_types.type_name AS type_name,"
				//Account device instance info
				+ " data_source_info.device_id AS device_id"
				+ " FROM ( " + combinedInnerQuery + " ) AS account_device_instances"
				+ " JOIN accounts AS accounts"
				+ "		ON accounts.account_id = account_device_instances.account_id"
				+ " JOIN account_types AS account_types"
				+ "		ON accounts.account_type_id = account_types.account_type_id"
				+ " JOIN data_source_info AS data_source_info"
				+ "		ON account_device_instances.data_source_obj_id = data_source_info.obj_id"
				+ (filterSQL.isEmpty() ? "" : " WHERE " + filterSQL);

		switch (db.getDatabaseType()) {
			case POSTGRESQL:
				queryStr = "SELECT DISTINCT ON ( accounts.account_id, data_source_info.device_id) " + queryStr;
				break;
			case SQLITE:
				queryStr = "SELECT " + queryStr + " GROUP BY accounts.account_id, data_source_info.device_id";
				break;
			default:
				throw new TskCoreException("Unknown DB Type: " + db.getDatabaseType().name());
		}

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryStr);) {
			ArrayList<AccountDeviceInstance> accountDeviceInstances = new ArrayList<AccountDeviceInstance>();
			while (rs.next()) {
				long account_id = rs.getLong("account_id");
				String deviceID = rs.getString("device_id");
				final String type_name = rs.getString("type_name");
				final String account_unique_identifier = rs.getString("account_unique_identifier");

				Account.Type accountType = typeNameToAccountTypeMap.get(type_name);
				Account account = new Account(account_id, accountType, account_unique_identifier);
				accountDeviceInstances.add(new AccountDeviceInstance(account, deviceID));
			}

			return accountDeviceInstances;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account device instances. " + ex.getMessage(), ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Get the sources (artifacts, content) of relationships between the given
	 * account device instances.
	 *
	 * Applicable filters: DeviceFilter, DateRangeFilter,
	 * RelationshipTypeFilter, MostRecentFilter
	 *
	 * @param account1 First AccountDeviceInstance
	 * @param account2 Second AccountDeviceInstance
	 * @param filter   Filters to apply.
	 *
	 * @return relationship sources for relationships between account1 and
	 *         account2.
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public List<Content> getRelationshipSources(AccountDeviceInstance account1, AccountDeviceInstance account2, CommunicationsFilter filter) throws TskCoreException {

		//set up applicable filters 
		Set<String> applicableFilters = new HashSet<>(Arrays.asList(
				CommunicationsFilter.DateRangeFilter.class.getName(),
				CommunicationsFilter.DeviceFilter.class.getName(),
				CommunicationsFilter.RelationshipTypeFilter.class.getName()
		));

		String limitQuery = " account_relationships AS relationships";
		String limitStr = getMostRecentFilterLimitSQL(filter);
		if (!limitStr.isEmpty()) {
			limitQuery = "(SELECT * FROM account_relationships as relationships " + limitStr + ") as relationships";
		}

		String filterSQL = getCommunicationsFilterSQL(filter, applicableFilters);
		final String queryString = "SELECT artifacts.artifact_id AS artifact_id,"
				+ "		artifacts.obj_id AS obj_id,"
				+ "		artifacts.artifact_obj_id AS artifact_obj_id,"
				+ "		artifacts.data_source_obj_id AS data_source_obj_id,"
				+ "		artifacts.artifact_type_id AS artifact_type_id,"
				+ "		artifacts.review_status_id AS review_status_id,"
				+ "     tsk_data_artifacts.os_account_obj_id AS os_account_obj_id"
				+ " FROM blackboard_artifacts AS artifacts"
				+ "	JOIN " + limitQuery
				+ "		ON artifacts.artifact_obj_id = relationships.relationship_source_obj_id"
				+ " LEFT JOIN tsk_data_artifacts ON artifacts.artifact_obj_id = tsk_data_artifacts.artifact_obj_id"
				+ " WHERE (( relationships.account1_id = " + account1.getAccount().getAccountID()
				+ " AND relationships.account2_id  = " + account2.getAccount().getAccountID()
				+ " ) OR (	  relationships.account2_id = " + account1.getAccount().getAccountID()
				+ " AND relationships.account1_id =" + account2.getAccount().getAccountID() + " ))"
				+ (filterSQL.isEmpty() ? "" : " AND " + filterSQL);

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryString);) {

			ArrayList<Content> artifacts = new ArrayList<>();
			artifacts.addAll(getDataArtifactsFromResult(rs));
			return artifacts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting relationships between accounts. " + ex.getMessage(), ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Get a list AccountFileInstance for the given accounts.
	 *
	 * @param account List of accounts
	 *
	 * @return	A lit of AccountFileInstances for the given accounts or null if
	 *         none are found.
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public List<AccountFileInstance> getAccountFileInstances(Account account) throws TskCoreException {
		List<AccountFileInstance> accountFileInstanceList = new ArrayList<>();
		@SuppressWarnings("deprecation")
		List<BlackboardArtifact> artifactList = getSleuthkitCase().getBlackboardArtifacts(BlackboardArtifact.ARTIFACT_TYPE.TSK_ACCOUNT, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ID, account.getTypeSpecificID());

		if (artifactList != null && !artifactList.isEmpty()) {
			for (BlackboardArtifact artifact : artifactList) {
				accountFileInstanceList.add(new AccountFileInstance(artifact, account));
			}
		}

		if (!accountFileInstanceList.isEmpty()) {
			return accountFileInstanceList;
		} else {
			return null;
		}
	}

	/**
	 * Gets a list of the distinct account types that can currently be found in
	 * the case db.
	 *
	 * @return A list of distinct accounts or an empty list.
	 *
	 * @throws TskCoreException
	 */
	public List<Account.Type> getAccountTypesInUse() throws TskCoreException {

		String query = "SELECT DISTINCT accounts.account_type_id, type_name, display_name FROM accounts JOIN account_types ON accounts.account_type_id = account_types.account_type_id";
		List<Account.Type> inUseAccounts = new ArrayList<>();

		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, query);) {
			Account.Type accountType;
			while (rs.next()) {
				String accountTypeName = rs.getString("type_name");
				accountType = this.typeNameToAccountTypeMap.get(accountTypeName);

				if (accountType == null) {
					accountType = new Account.Type(accountTypeName, rs.getString("display_name"));
					this.accountTypeToTypeIdMap.put(accountType, rs.getInt("account_type_id"));
				}

				inUseAccounts.add(accountType);
			}
			return inUseAccounts;
		} catch (SQLException ex) {
			throw new TskCoreException("Error getting account type id", ex);
		} finally {
			db.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * Gets a list of accounts that are related to the given artifact.
	 *
	 * @param artifact
	 *
	 * @return A list of distinct accounts or an empty list if none where found.
	 *
	 * @throws TskCoreException
	 */
	public List<Account> getAccountsRelatedToArtifact(BlackboardArtifact artifact) throws TskCoreException {
		if (artifact == null) {
			throw new IllegalArgumentException("null arugment passed to getAccountsRelatedToArtifact");
		}

		List<Account> accountList = new ArrayList<>();
		db.acquireSingleUserCaseReadLock();
		try (CaseDbConnection connection = db.getConnection()) {
			try {
				// In order to get a list of all the unique accounts in a relationship with the given aritfact
				// we must first union a list of the unique account1_id in the relationship with artifact
				// then the unique account2_id (inner select with union).  The outter select assures the list
				// of the inner select only contains unique accounts.
				String query = String.format("SELECT DISTINCT (account_id), account_type_id, account_unique_identifier"
						+ "	FROM ("
						+ " SELECT DISTINCT (account_id), account_type_id, account_unique_identifier"
						+ " FROM accounts"
						+ " JOIN account_relationships ON account1_id = account_id"
						+ " WHERE relationship_source_obj_id = %d"
						+ " UNION "
						+ " SELECT DISTINCT (account_id), account_type_id, account_unique_identifier"
						+ " FROM accounts"
						+ " JOIN account_relationships ON account2_id = account_id"
						+ " WHERE relationship_source_obj_id = %d) AS unionOfRelationships", artifact.getId(), artifact.getId());
				try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
					while (rs.next()) {
						Account.Type accountType = null;
						int accountTypeId = rs.getInt("account_type_id");
						for (Map.Entry<Account.Type, Integer> entry : accountTypeToTypeIdMap.entrySet()) {
							if (entry.getValue() == accountTypeId) {
								accountType = entry.getKey();
								break;
							}
						}

						accountList.add(new Account(rs.getInt("account_id"), accountType, rs.getString("account_unique_identifier")));
					}
				} catch (SQLException ex) {
					throw new TskCoreException("Unable to get account list for give artifact " + artifact.getId(), ex);
				}

			} finally {
				db.releaseSingleUserCaseReadLock();
			}
		}

		return accountList;
	}

	/**
	 * Get account_type_id for the given account type.
	 *
	 * @param accountType account type to lookup.
	 *
	 * @return account_type_id for the given account type. 0 if not known.
	 */
	int getAccountTypeId(Account.Type accountType) {
		if (accountTypeToTypeIdMap.containsKey(accountType)) {
			return accountTypeToTypeIdMap.get(accountType);
		}

		return 0;
	}

	/**
	 * Normalize the given account ID according to the rules of the given
	 * Account.Type.
	 *
	 * @param accountType     The type of account to normalize for
	 * @param accountUniqueID The account id to normalize
	 *
	 * @return The normalized account id.
	 *
	 * @throws InvalidAccountIDException If the account identifier is not valid.
	 */
	private String normalizeAccountID(Account.Type accountType, String accountUniqueID) throws InvalidAccountIDException {

		if (accountUniqueID == null || accountUniqueID.isEmpty()) {
			throw new InvalidAccountIDException("Account id is null or empty.");
		}

		String normalizedAccountID;
		if (accountType.equals(Account.Type.PHONE)) {
			normalizedAccountID = CommunicationsUtils.normalizePhoneNum(accountUniqueID);
		} else if (accountType.equals(Account.Type.EMAIL)) {
			normalizedAccountID = CommunicationsUtils.normalizeEmailAddress(accountUniqueID);
		} else {
			normalizedAccountID = accountUniqueID.toLowerCase().trim();
		}

		return normalizedAccountID;
	}

	/**
	 * Builds the SQL for the given CommunicationsFilter.
	 *
	 * Gets the SQL for each subfilter and combines using AND.
	 *
	 * @param commFilter        The CommunicationsFilter to get the SQL for.
	 * @param applicableFilters A Set of names of classes of subfilters that are
	 *                          applicable. SubFilters not in this list will be
	 *                          ignored.
	 *
	 * @return return SQL suitible for use IN a where clause.
	 */
	private String getCommunicationsFilterSQL(CommunicationsFilter commFilter, Set<String> applicableFilters) {
		if (null == commFilter || commFilter.getAndFilters().isEmpty()) {
			return "";
		}

		String sqlStr = "";
		StringBuilder sqlSB = new StringBuilder();
		boolean first = true;
		for (CommunicationsFilter.SubFilter subFilter : commFilter.getAndFilters()) {

			// If the filter is applicable
			if (applicableFilters.contains(subFilter.getClass().getName())) {
				String subfilterSQL = subFilter.getSQL(this);
				if (!subfilterSQL.isEmpty()) {
					if (first) {
						first = false;
					} else {
						sqlSB.append(" AND ");
					}
					sqlSB.append("( ");
					sqlSB.append(subfilterSQL);
					sqlSB.append(" )");
				}
			}
		}

		if (!sqlSB.toString().isEmpty()) {
			sqlStr = "( " + sqlSB.toString() + " )";
		}
		return sqlStr;
	}

	/**
	 * Builds the SQL for the MostRecentFilter.
	 *
	 * @param filter	The CommunicationsFilter to get the SQL for.
	 *
	 * @return	Order BY and LIMIT clause or empty string if no filter is
	 *         available.
	 */
	private String getMostRecentFilterLimitSQL(CommunicationsFilter filter) {
		String limitStr = "";

		if (filter != null && !filter.getAndFilters().isEmpty()) {

			for (CommunicationsFilter.SubFilter subFilter : filter.getAndFilters()) {
				if (subFilter.getClass().getName().equals(CommunicationsFilter.MostRecentFilter.class.getName())) {
					limitStr = subFilter.getSQL(this);
					break;
				}
			}
		}

		return limitStr;
	}

	/**
	 * A helper method that will return a set of BlackboardArtifact objects for
	 * the given ResultSet.
	 *
	 * @param resultSet	The results of executing a query.
	 *
	 * @return A list of BlackboardArtifact objects.
	 *
	 * @throws SQLException
	 * @throws TskCoreException
	 */
	private List<BlackboardArtifact> getDataArtifactsFromResult(ResultSet resultSet) throws SQLException, TskCoreException {
		List<BlackboardArtifact> artifacts = new ArrayList<>();
		while (resultSet.next()) {
			BlackboardArtifact.Type bbartType = db.getBlackboard().getArtifactType(resultSet.getInt("artifact_type_id"));
			artifacts.add(new DataArtifact(db, resultSet.getLong("artifact_id"),
					resultSet.getLong("obj_id"), resultSet.getLong("artifact_obj_id"),
					resultSet.getObject("data_source_obj_id") != null ? resultSet.getLong("data_source_obj_id") : null,
					bbartType.getTypeID(),
					bbartType.getTypeName(), bbartType.getDisplayName(),
					BlackboardArtifact.ReviewStatus.withID(resultSet.getInt("review_status_id")),
					resultSet.getLong("os_account_obj_id"), false));
		}

		return artifacts;
	}
}
