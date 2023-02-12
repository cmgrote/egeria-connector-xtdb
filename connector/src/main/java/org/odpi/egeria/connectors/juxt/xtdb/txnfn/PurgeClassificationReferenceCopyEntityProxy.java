/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.juxt.xtdb.txnfn;

import clojure.lang.*;
import org.odpi.egeria.connectors.juxt.xtdb.auditlog.XtdbOMRSErrorCode;
import org.odpi.egeria.connectors.juxt.xtdb.cache.ErrorMessageCache;
import org.odpi.egeria.connectors.juxt.xtdb.mapping.ClassificationMapping;
import org.odpi.egeria.connectors.juxt.xtdb.mapping.EntityDetailMapping;
import org.odpi.egeria.connectors.juxt.xtdb.mapping.EntityProxyMapping;
import org.odpi.egeria.connectors.juxt.xtdb.mapping.InstanceAuditHeaderMapping;
import org.odpi.egeria.connectors.juxt.xtdb.repositoryconnector.XtdbOMRSRepositoryConnector;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.Classification;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.EntityDetail;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.EntityProxy;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xtdb.api.TransactionInstant;
import xtdb.api.XtdbDocument;
import xtdb.api.tx.Transaction;

/**
 * Transaction function for adding a reference copy classification.
 */
public class PurgeClassificationReferenceCopyEntityProxy extends PurgeClassificationReferenceCopy {

    private static final Logger log = LoggerFactory.getLogger(PurgeClassificationReferenceCopyEntityProxy.class);

    public static final Keyword FUNCTION_NAME = Keyword.intern("egeria", "purgeClassificationReferenceCopyEntityProxy");
    private static final String CLASS_NAME = PurgeClassificationReferenceCopyEntityProxy.class.getName();
    private static final String METHOD_NAME = FUNCTION_NAME.toString();

    /**
     * Constructor used to execute the transaction function.
     * @param txId the transaction ID of this function invocation
     * @param existing the existing entity in XT, if any
     * @param proxy the entity (proxy) from which to purge the classification
     * @param entityGUID GUID of the entity from which we are purging the classification
     * @param classification the classification to persist as a reference copy
     * @param homeMetadataCollectionId the metadataCollectionId of the repository where the transaction is running
     * @throws Exception on any error
     */
    public PurgeClassificationReferenceCopyEntityProxy(Long txId,
                                                       PersistentHashMap existing,
                                                       PersistentHashMap proxy,
                                                       String entityGUID,
                                                       Classification classification,
                                                       String homeMetadataCollectionId)
            throws Exception {
        super(CLASS_NAME, METHOD_NAME, txId, existing, proxy, entityGUID, classification, homeMetadataCollectionId);
    }

    /**
     * Permanently remove the provided classification from the XTDB repository by pushing down the transaction.
     * @param xtdb connectivity
     * @param toPurgeFrom the entity from which to remove the classification
     * @param classification to permanently remove
     * @throws EntityConflictException the new entity conflicts with an existing entity
     * @throws RepositoryErrorException on any other error
     */
    public static void transact(XtdbOMRSRepositoryConnector xtdb,
                                EntityProxy toPurgeFrom,
                                Classification classification)
            throws EntityConflictException, RepositoryErrorException {
        String docId = EntityDetailMapping.getReference(toPurgeFrom.getGUID());
        EntityProxyMapping epm = new EntityProxyMapping(xtdb, toPurgeFrom);
        XtdbDocument toPurgeFromXT = epm.toXTDB();
        Transaction.Builder tx = Transaction.builder();
        tx.invokeFunction(FUNCTION_NAME, docId, toPurgeFromXT.toMap(), classification, xtdb.getMetadataCollectionId());
        TransactionInstant results = xtdb.runTx(tx.build());
        try {
            xtdb.validateCommit(results, METHOD_NAME);
        } catch (EntityConflictException | RepositoryErrorException e) {
            throw e;
        } catch (Exception e) {
            throw new RepositoryErrorException(XtdbOMRSErrorCode.UNKNOWN_RUNTIME_ERROR.getMessageDefinition(),
                    CLASS_NAME,
                    METHOD_NAME,
                    e);
        }
    }

    /**
     * Interface that returns the updated document to write-back from the transaction.
     * @return IPersistentMap giving the updated document in its entirety
     */
    public IPersistentMap doc() {
        log.debug("Entity being persisted: {}", xtdbDoc);
        return xtdbDoc;
    }

    /**
     * Create the transaction function within XTDB.
     * @param tx transaction through which to create the function
     */
    public static void create(Transaction.Builder tx) {
        createTransactionFunction(tx, FUNCTION_NAME, getTxFn(CLASS_NAME));
    }

}
