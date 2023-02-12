/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.juxt.xtdb.txnfn;

import clojure.lang.*;
import org.odpi.egeria.connectors.juxt.xtdb.auditlog.XtdbOMRSErrorCode;
import org.odpi.egeria.connectors.juxt.xtdb.mapping.EntityDetailMapping;
import org.odpi.egeria.connectors.juxt.xtdb.repositoryconnector.XtdbOMRSRepositoryConnector;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.Classification;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.EntityDetail;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xtdb.api.TransactionInstant;
import xtdb.api.XtdbDocument;
import xtdb.api.tx.Transaction;

/**
 * Transaction function for adding a reference copy classification.
 */
public class SaveClassificationReferenceCopyEntityDetail extends SaveClassificationReferenceCopy {

    private static final Logger log = LoggerFactory.getLogger(SaveClassificationReferenceCopyEntityDetail.class);

    public static final Keyword FUNCTION_NAME = Keyword.intern("egeria", "saveClassificationReferenceCopyEntityDetail");
    private static final String CLASS_NAME = SaveClassificationReferenceCopyEntityDetail.class.getName();
    private static final String METHOD_NAME = FUNCTION_NAME.toString();

    /**
     * Constructor used to execute the transaction function.
     * @param txId the transaction ID of this function invocation
     * @param entityGUID unique identifier of the existing entity
     * @param existing the existing entity in XT, if any
     * @param entity the entity against which to apply the classification
     * @param classification the classification to persist as a reference copy
     * @param homeMetadataCollectionId the metadataCollectionId of the repository where the transaction is running
     * @throws Exception on any error
     */
    public SaveClassificationReferenceCopyEntityDetail(Long txId,
                                                       String entityGUID,
                                                       PersistentHashMap existing,
                                                       PersistentHashMap entity,
                                                       Classification classification,
                                                       String homeMetadataCollectionId)
            throws Exception {
        super(CLASS_NAME, METHOD_NAME, txId, entityGUID, existing, entity, classification, homeMetadataCollectionId);
    }

    /**
     * Create the provided classification in the XTDB repository by pushing down the transaction.
     * @param xtdb connectivity
     * @param toStoreAgainst the entity against which to persist the classification
     * @param classification to persist as a reference copy
     * @throws EntityConflictException the new entity conflicts with an existing entity
     * @throws TypeErrorException the requested type is not known or not supported
     * @throws PropertyErrorException one or more of the requested properties are not defined or have different characteristics in the TypeDef for this classification type
     * @throws InvalidParameterException one of the parameters is invalid or null
     * @throws RepositoryErrorException on any other error
     */
    public static void transact(XtdbOMRSRepositoryConnector xtdb,
                                EntityDetail toStoreAgainst,
                                Classification classification)
            throws EntityConflictException, TypeErrorException, PropertyErrorException, InvalidParameterException, RepositoryErrorException {
        String docId = EntityDetailMapping.getReference(toStoreAgainst.getGUID());
        EntityDetailMapping edm = new EntityDetailMapping(xtdb, toStoreAgainst);
        XtdbDocument toStoreAgainstXT = edm.toXTDB();
        Transaction.Builder tx = Transaction.builder();
        tx.invokeFunction(FUNCTION_NAME, docId, toStoreAgainstXT.toMap(), classification, xtdb.getMetadataCollectionId());
        TransactionInstant results = xtdb.runTx(tx.build());
        try {
            xtdb.validateCommit(results, METHOD_NAME);
        } catch (EntityConflictException | TypeErrorException | PropertyErrorException | InvalidParameterException | RepositoryErrorException e) {
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
