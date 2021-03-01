/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.juxt.crux.mapping;

import crux.api.CruxDocument;
import crux.api.ICruxDatasource;
import org.odpi.egeria.connectors.juxt.crux.repositoryconnector.CruxOMRSRepositoryConnector;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.*;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.RepositoryErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps the properties of Relationships between persistence and objects.
 */
public class RelationshipMapping extends InstanceHeaderMapping {

    private static final Logger log = LoggerFactory.getLogger(RelationshipMapping.class);

    public static final String INSTANCE_REF_PREFIX = "r";

    public static final String RELATIONSHIP_PROPERTIES_NS = "relationshipProperties";
    public static final String N_ENTITY_ONE_PROXY = "entityOneProxy";
    public static final String N_ENTITY_TWO_PROXY = "entityTwoProxy";

    public static final String ENTITY_ONE_PROXY = getKeyword(N_ENTITY_ONE_PROXY);
    public static final String ENTITY_TWO_PROXY = getKeyword(N_ENTITY_TWO_PROXY);

    private ICruxDatasource db;

    /**
     * Construct a mapping from a Relationship (to map to a Crux representation).
     * @param cruxConnector connectivity to Crux
     * @param relationship from which to map
     */
    public RelationshipMapping(CruxOMRSRepositoryConnector cruxConnector,
                               Relationship relationship) {
        super(cruxConnector, relationship);
    }

    /**
     * Construct a mapping from a Crux map (to map to an Egeria representation).
     * @param cruxConnector connectivity to Crux
     * @param cruxDoc from which to map
     * @param db an open database connection for a point-in-time appropriate to the mapping
     */
    public RelationshipMapping(CruxOMRSRepositoryConnector cruxConnector,
                               CruxDocument cruxDoc,
                               ICruxDatasource db) {
        super(cruxConnector, cruxDoc);
        this.db = db;
    }

    /**
     * Map from Crux to Egeria.
     * @return EntityDetail
     * @see #RelationshipMapping(CruxOMRSRepositoryConnector, CruxDocument, ICruxDatasource)
     */
    public Relationship toEgeria() {
        if (instanceHeader == null && cruxDoc != null) {
            instanceHeader = new Relationship();
            fromDoc();
        }
        if (instanceHeader != null) {
            return (Relationship) instanceHeader;
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected CruxDocument.Builder toDoc() {
        CruxDocument.Builder builder = super.toDoc();
        Relationship relationship = (Relationship) instanceHeader;
        EntityProxy one = relationship.getEntityOneProxy();
        EntityProxy two = relationship.getEntityTwoProxy();
        builder.put(ENTITY_ONE_PROXY, EntityProxyMapping.getReference(one.getGUID()));
        builder.put(ENTITY_TWO_PROXY, EntityProxyMapping.getReference(two.getGUID()));
        InstancePropertiesMapping.addToDoc(cruxConnector, builder, relationship.getType(), relationship.getProperties(), RELATIONSHIP_PROPERTIES_NS);
        return builder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fromDoc() {
        super.fromDoc();
        try {
            Object oneRef = cruxDoc.get(ENTITY_ONE_PROXY);
            if (oneRef instanceof String) {
                EntityProxy one = getEntityProxyFromRef((String) oneRef);
                ((Relationship) instanceHeader).setEntityOneProxy(one);
            }
            Object twoRef = cruxDoc.get(ENTITY_TWO_PROXY);
            if (twoRef instanceof String) {
                EntityProxy two = getEntityProxyFromRef((String) twoRef);
                ((Relationship) instanceHeader).setEntityTwoProxy(two);
            }
            InstanceProperties ip = InstancePropertiesMapping.getFromDoc(instanceHeader.getType(), cruxDoc, RELATIONSHIP_PROPERTIES_NS);
            ((Relationship) instanceHeader).setProperties(ip);
        } catch (RepositoryErrorException e) {
            log.error("Unable to retrieve entity proxy, nullifying the relationship.", e);
            instanceHeader = null;
        }
    }

    /**
     * Retrieve the entity proxy details from the provided reference.
     * @param ref to the entity proxy
     * @return EntityProxy
     * @throws RepositoryErrorException logic error in the repository with corrupted entity proxy
     */
    private EntityProxy getEntityProxyFromRef(String ref) throws RepositoryErrorException {
        CruxDocument epDoc = cruxConnector.getCruxObjectByReference(db, ref);
        return EntityProxyMapping.getFromDoc(cruxConnector, epDoc);
    }

    /**
     * Retrieve the canonical reference to the relationship with the specified GUID.
     * @param guid of the relationship to reference
     * @return String giving the Crux reference to this relationship document
     */
    public static String getReference(String guid) {
        return getGuid(INSTANCE_REF_PREFIX, guid);
    }

}
