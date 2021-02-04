/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.juxt.crux.model.search;

import clojure.lang.*;
import org.odpi.egeria.connectors.juxt.crux.mapping.*;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.MatchCriteria;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.SequencingOrder;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.*;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.search.*;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryconnector.OMRSRepositoryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Captures the structure of a query against Crux.
 */
public class CruxQuery {

    private static final Logger log = LoggerFactory.getLogger(CruxQuery.class);

    // Variable names (for sorting)
    private static final Symbol DOC_ID = Symbol.intern("e");
    private static final Symbol CREATE_TIME = Symbol.intern("ct");
    private static final Symbol UPDATE_TIME = Symbol.intern("ut");
    private static final Symbol SORT_PROPERTY = Symbol.intern("sp");

    // Sort orders
    private static final Keyword SORT_ASCENDING = Keyword.intern("asc");
    private static final Keyword SORT_DESCENDING = Keyword.intern("desc");

    // Predicates (for comparisons)
    private static final Symbol OR_OPERATOR = Symbol.intern("or");
    private static final Symbol AND_OPERATOR = Symbol.intern("and");
    private static final Symbol NOT_OPERATOR = Symbol.intern("not");
    private static final Symbol OR_JOIN = Symbol.intern("or-join");
    private static final Symbol GT_OPERATOR = Symbol.intern(">");
    private static final Symbol GTE_OPERATOR = Symbol.intern(">=");
    private static final Symbol LT_OPERATOR = Symbol.intern("<");
    private static final Symbol LTE_OPERATOR = Symbol.intern("<=");
    private static final Symbol IS_NULL_OPERATOR = Symbol.intern("nil?");
    private static final Symbol NOT_NULL_OPERATOR = Symbol.intern("some?");
    private static final Symbol REGEX_OPERATOR = Symbol.intern("re-matches");
    private static final Symbol IN_OPERATOR = null; // TODO

    private static final Map<PropertyComparisonOperator, Symbol> PCO_TO_SYMBOL = createPropertyComparisonOperatorToSymbolMap();
    private static Map<PropertyComparisonOperator, Symbol> createPropertyComparisonOperatorToSymbolMap() {
        Map<PropertyComparisonOperator, Symbol> map = new HashMap<>();
        map.put(PropertyComparisonOperator.GT, GT_OPERATOR);
        map.put(PropertyComparisonOperator.GTE, GTE_OPERATOR);
        map.put(PropertyComparisonOperator.LT, LT_OPERATOR);
        map.put(PropertyComparisonOperator.LTE, LTE_OPERATOR);
        map.put(PropertyComparisonOperator.IS_NULL, IS_NULL_OPERATOR);
        map.put(PropertyComparisonOperator.NOT_NULL, NOT_NULL_OPERATOR);
        map.put(PropertyComparisonOperator.LIKE, REGEX_OPERATOR);
        map.put(PropertyComparisonOperator.IN, IN_OPERATOR);
        return map;
    }

    private IPersistentMap query;
    private final List<Symbol> findElements;
    private final List<IPersistentCollection> conditions;
    private final List<IPersistentVector> sequencing;
    private int limit = Constants.DEFAULT_PAGE_SIZE;
    private int offset = 0;

    /**
     * Default constructor for a new query.
     */
    public CruxQuery() {
        query = PersistentArrayMap.EMPTY;
        findElements = new ArrayList<>();
        findElements.add(DOC_ID); // Always have the DocID itself as the first element, to ease parsing of results
        conditions = new ArrayList<>();
        sequencing = new ArrayList<>();
    }

    /**
     * Add a condition to match either endpoint of a relationship to the provided reference (primary key).
     * @param reference the primary key value of an entity, used to match either end of a relationship
     */
    public void addRelationshipEndpointConditions(Keyword reference) {
        List<Object> orConditions = new ArrayList<>();
        orConditions.add(OR_OPERATOR);
        orConditions.add(getReferenceCondition(RelationshipMapping.ENTITY_ONE_PROXY, reference));
        orConditions.add(getReferenceCondition(RelationshipMapping.ENTITY_TWO_PROXY, reference));
        conditions.add(PersistentList.create(orConditions));
    }

    /**
     * Add a condition to match the value of a property to a reference (primary key).
     * @param property to match
     * @param reference the primary key value to which the property should refer
     * @return PersistentVector for the condition
     */
    protected PersistentVector getReferenceCondition(Keyword property, Keyword reference) {
        return PersistentVector.create(DOC_ID, property, reference);
    }

    /**
     * Add a condition to limit the type of the results by their TypeDef GUID.
     * @param typeGuid by which to limit the results (if null, will be ignored)
     * @param subtypeLimits limit the results to only these subtypes (if provided: ignored if typeGuid is null)
     */
    public void addTypeCondition(String typeGuid, List<String> subtypeLimits) {
        if (typeGuid != null) {
            List<Object> orConditions = new ArrayList<>();
            orConditions.add(OR_OPERATOR);
            if (subtypeLimits != null && !subtypeLimits.isEmpty()) {
                // If subtypes were specified, search only for those (explicitly)
                for (String subtypeGuid : subtypeLimits) {
                    orConditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.TYPE_DEF_GUID, subtypeGuid));
                    orConditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.SUPERTYPE_DEF_GUIDS, subtypeGuid));
                }
            } else {
                // Otherwise, search for any matches against the typeGuid exactly or where it is a supertype
                // - exactly matching the TypeDef:  [e :type.guid "..."]
                orConditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.TYPE_DEF_GUID, typeGuid));
                // - matching any of the super types:  [e :type.supers "...]
                orConditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.SUPERTYPE_DEF_GUIDS, typeGuid));
            }
            conditions.add(PersistentList.create(orConditions));
        }
    }

    /**
     * Retrieve the set of conditions appropriate to Crux for the provided Egeria conditions.
     * @param searchProperties to translate
     * @param namespace by which to qualify properties
     * @param typeNames of all of the types we are including in the search
     * @param repositoryHelper through which we can lookup type information and properties
     * @param repositoryName of the repository (for logging)
     */
    public void addPropertyConditions(SearchProperties searchProperties,
                                      String namespace,
                                      Set<String> typeNames,
                                      OMRSRepositoryHelper repositoryHelper,
                                      String repositoryName) {
        List<IPersistentCollection> cruxConditions = getPropertyConditions(searchProperties, namespace, false, typeNames, repositoryHelper, repositoryName);
        if (cruxConditions != null) {
            conditions.addAll(cruxConditions);
        }
    }

    /**
     * Retrieve the set of conditions appropriate to Crux for the provided Egeria conditions.
     * @param searchClassifications to translate
     * @param typeNames of all of the types we are including in the search
     * @param repositoryHelper through which we can lookup type information and properties
     * @param repositoryName of the repository (for logging)
     */
    public void addClassificationConditions(SearchClassifications searchClassifications,
                                            Set<String> typeNames,
                                            OMRSRepositoryHelper repositoryHelper,
                                            String repositoryName) {
        List<IPersistentCollection> cruxConditions = getClassificationConditions(searchClassifications, typeNames, repositoryHelper, repositoryName);
        if (cruxConditions != null) {
            conditions.addAll(cruxConditions);
        }
    }

    /**
     * Retrieve a set of translated Crux conditions appropriate to the provided Egeria conditions.
     * @param searchClassifications to translate
     * @param typeNames of all of the types we are including in the search
     * @param repositoryHelper through which we can lookup type information and properties
     * @param repositoryName of the repository (for logging)
     * @return {@code List<IPersistentCollection>}
     */
    protected List<IPersistentCollection> getClassificationConditions(SearchClassifications searchClassifications,
                                                                      Set<String> typeNames,
                                                                      OMRSRepositoryHelper repositoryHelper,
                                                                      String repositoryName) {
        if (searchClassifications != null) {
            // Since classifications can only be applied to entities, we can draw the namespace directly
            String namespace = EntitySummaryMapping.N_CLASSIFICATIONS;
            List<ClassificationCondition> classificationConditions = searchClassifications.getConditions();
            MatchCriteria matchCriteria = searchClassifications.getMatchCriteria();
            if (classificationConditions != null && !classificationConditions.isEmpty()) {
                List<IPersistentCollection> allConditions = new ArrayList<>();
                for (ClassificationCondition condition : classificationConditions) {
                    String classificationName = condition.getName();
                    // TODO: if there are multiple classification names, is there a risk this is interpreted as an AND
                    //  that the entity needs to possess ALL of these classifications (irrespective of the MatchCriteria)?
                    allConditions.add(PersistentVector.create(DOC_ID, Keyword.intern(namespace), classificationName));
                    String qualifiedNamespace = ClassificationMapping.getNamespaceForClassification(namespace, classificationName);
                    List<IPersistentCollection> matchConditions = getPropertyConditions(
                            condition.getMatchProperties(),
                            qualifiedNamespace,
                            matchCriteria.equals(MatchCriteria.ANY),
                            typeNames,
                            repositoryHelper,
                            repositoryName);
                    if (matchConditions != null) {
                        allConditions.addAll(matchConditions);
                    }
                }
                return allConditions;
            }
        }
        return null;
    }

    /**
     * Retrieve a set of translated Crux conditions appropriate to the provided Egeria conditions.
     * @param searchProperties to translate
     * @param namespace by which to qualify properties
     * @param orNested true iff searchProperties is a set of conditions nested inside an OR (match criteria = ANY)
     * @param typeNames of all of the types we are including in the search
     * @param repositoryHelper through which we can lookup type information and properties
     * @param repositoryName of the repository (for logging)
     * @return {@code List<IPersistentCollection>}
     */
    protected List<IPersistentCollection> getPropertyConditions(SearchProperties searchProperties,
                                                                String namespace,
                                                                boolean orNested,
                                                                Set<String> typeNames,
                                                                OMRSRepositoryHelper repositoryHelper,
                                                                String repositoryName) {
        if (searchProperties != null) {
            List<PropertyCondition> propertyConditions = searchProperties.getConditions();
            MatchCriteria matchCriteria = searchProperties.getMatchCriteria();
            if (propertyConditions != null && !propertyConditions.isEmpty()) {
                List<IPersistentCollection> allConditions = new ArrayList<>();
                for (PropertyCondition condition : propertyConditions) {
                    // Ensure every condition, whether nested or singular, is added to the 'allConditions' list
                    List<IPersistentCollection> cruxConditions = getSinglePropertyCondition(
                            condition,
                            matchCriteria,
                            namespace,
                            typeNames,
                            repositoryHelper,
                            repositoryName
                    );
                    if (cruxConditions != null && !cruxConditions.isEmpty()) {
                        allConditions.addAll(cruxConditions);
                    }
                }
                // apply the matchCriteria against the full set of nested property conditions
                List<Object> predicatedConditions = new ArrayList<>();
                switch (matchCriteria) {
                    case ALL:
                        // TODO: do we need to make this distinction any longer?
                        if (orNested) {
                            // we should only wrap with an 'AND' predicate if we're nested inside an 'OR' predicate
                            predicatedConditions.add(AND_OPERATOR);
                            predicatedConditions.addAll(allConditions);
                        } else {
                            // otherwise, we can return the conditions directly (nothing more to process on them)
                            return allConditions;
                        }
                        break;
                    case ANY:
                        // (or-join [e] (and [e :property var] [(predicate ... var)]) )
                        predicatedConditions.add(OR_JOIN);
                        predicatedConditions.add(PersistentVector.create(DOC_ID));
                        predicatedConditions.addAll(allConditions);
                        break;
                    case NONE:
                        // (not (or-join [e] ... ) )
                        predicatedConditions.add(NOT_OPERATOR);
                        List<Object> orJoin = new ArrayList<>();
                        orJoin.add(OR_JOIN);
                        orJoin.add(PersistentVector.create(DOC_ID));
                        orJoin.addAll(allConditions);
                        predicatedConditions.add(PersistentList.create(orJoin));
                        break;
                    default:
                        log.warn("Unmapped match criteria: {}", matchCriteria);
                        break;
                }
                if (!predicatedConditions.isEmpty()) {
                    List<IPersistentCollection> wrapped = new ArrayList<>();
                    wrapped.add(getCruxCondition(predicatedConditions));
                    return wrapped;
                }
            }
        }
        return null;
    }

    /**
     * Translate the provided condition, considered on its own, into a Crux query condition. Handles both single
     * property conditions and nested conditions (though the latter simply recurse back to getPropertyConditions)
     * @param singleCondition to translate (should not contain nested condition)
     * @param outerCriteria the outer match criteria in which this condition is contained
     * @param namespace by which to qualify the properties in the condition
     * @param typeNames of all of the types we are including in the search
     * @param repositoryHelper through which we can lookup type information and properties
     * @param repositoryName of the repository (for logging)
     * @return {@code List<IPersistentCollection>} giving the appropriate Crux query condition(s)
     * @see #getPropertyConditions(SearchProperties, String, boolean, Set, OMRSRepositoryHelper, String)
     */
    protected List<IPersistentCollection> getSinglePropertyCondition(PropertyCondition singleCondition,
                                                                     MatchCriteria outerCriteria,
                                                                     String namespace,
                                                                     Set<String> typeNames,
                                                                     OMRSRepositoryHelper repositoryHelper,
                                                                     String repositoryName) {
        SearchProperties nestedConditions = singleCondition.getNestedConditions();
        if (nestedConditions != null) {
            // If the conditions are nested, simply recurse back on getPropertyConditions
            MatchCriteria matchCriteria = nestedConditions.getMatchCriteria();
            return getPropertyConditions(
                    nestedConditions,
                    namespace,
                    matchCriteria.equals(MatchCriteria.ANY),
                    typeNames,
                    repositoryHelper,
                    repositoryName);
        } else {
            // Otherwise, parse through and process a single value condition
            String simpleName = singleCondition.getProperty();
            PropertyComparisonOperator comparator = singleCondition.getOperator();
            InstancePropertyValue value = singleCondition.getValue();
            if (InstanceAuditHeaderMapping.KNOWN_PROPERTIES.contains(simpleName)) {
                // InstanceAuditHeader properties should neither be namespace-d nor '.value' qualified, as they are not
                // InstanceValueProperties but simple native types
                Keyword propertyRef = getAuditHeaderPropertyRef(namespace, simpleName);
                return getConditionForPropertyRef(propertyRef, comparator, value, outerCriteria, Symbol.intern(simpleName));
            } else {
                // Any others we should assume are InstanceProperties, which will need namespace AND type AND '.value'
                // qualification to be searchable (of which there could be multiple, for a single given property, if
                // we are searching from high up the supertype tree)
                Set<Keyword> qualifiedSearchProperties;
                if (namespace.startsWith(EntitySummaryMapping.N_CLASSIFICATIONS) && !ClassificationMapping.KNOWN_PROPERTIES.contains(simpleName)) {
                    // Once again, if they are classification-specific instance properties, they need further qualification
                    String classificationNamespace = namespace + "." + ClassificationMapping.CLASSIFICATION_PROPERTIES_NS;
                    // Given the namespace qualification places into classificationProperties, we should ONLY need to
                    // search the classification type(s) for this property -- not all types
                    Set<String> classificationTypes = new HashSet<>();
                    String classificationTypeName = ClassificationMapping.getClassificationNameFromNamespace(EntitySummaryMapping.N_CLASSIFICATIONS, namespace);
                    classificationTypes.add(classificationTypeName);
                    qualifiedSearchProperties = InstancePropertyValueMapping.getNamesForProperty(repositoryName,
                            repositoryHelper,
                            simpleName,
                            classificationNamespace,
                            classificationTypes,
                            value);
                } else {
                    qualifiedSearchProperties = InstancePropertyValueMapping.getNamesForProperty(repositoryName,
                            repositoryHelper,
                            simpleName,
                            namespace,
                            typeNames,
                            value);
                }
                if (qualifiedSearchProperties.isEmpty()) {
                    log.warn("The provided property '{}' (as {}) did not match any of the type definition restrictions: {} -- forcing no results.", simpleName, value, typeNames);
                    return getNoResultsCondition();
                } else {
                    // Since depending on the types by which we are limiting the search there could be different variations
                    // of the same property name, we need to include criteria to find all of them -- so we must iterate
                    // through and build up a set of conditions for each variation of the property
                    List<IPersistentCollection> allPropertyConditions = new ArrayList<>();
                    Symbol symbolForVariable = Symbol.intern(simpleName);
                    List<IPersistentCollection> conditionAggregator = new ArrayList<>();
                    for (Keyword qualifiedPropertyRef : qualifiedSearchProperties) {
                        List<IPersistentCollection> conditionsForOneProperty = getConditionForPropertyRef(qualifiedPropertyRef, comparator, value, outerCriteria, symbolForVariable);
                        if (conditionsForOneProperty.size() > 1) {
                            // There are cases where the above could return more than one condition, in which case we should
                            // (and )-wrap it, since we'll be within an or-join
                            List<Object> andList = new ArrayList<>();
                            andList.add(AND_OPERATOR);
                            andList.addAll(conditionsForOneProperty);
                            conditionAggregator.add(PersistentList.create(andList));
                        } else {
                            // This is really just adding the single embedded element, without risking a .get() IndexOutOfBounds
                            conditionAggregator.addAll(conditionsForOneProperty);
                        }
                    }
                    if (MatchCriteria.ALL.equals(outerCriteria)) {
                        // If the outer criteria is ALL, then we will need to or-join the combined set of conditions, as
                        // only one of the property variations needs to match to meet that criteria.
                        List<Object> orJoin = new ArrayList<>();
                        orJoin.add(OR_JOIN);
                        orJoin.add(PersistentVector.create(DOC_ID));
                        orJoin.addAll(conditionAggregator);
                        allPropertyConditions.add(PersistentList.create(orJoin));
                    } else {
                        // For a NONE, we actually want to ensure that all of the conditions are AND'd, so no need to
                        // wrap. And for an ANY, there will already be an or-join wrapped around the conditions by the
                        // caller of this method, so no need to wrap them again here. Therefore, in both cases, we just
                        // need to put all the conditions together and return them.
                        allPropertyConditions.addAll(conditionAggregator);
                    }
                    return allPropertyConditions;
                }
            }
        }
    }

    /**
     * Retrieve the reference to use for an audit header property (these are generally unqualified (not namespaced),
     * unless they are embedded within a classification).
     * @param namespace by which to qualify the property (for classifications)
     * @param propertyName for which to retrieve a reference
     * @return Keyword
     */
    protected Keyword getAuditHeaderPropertyRef(String namespace, String propertyName) {
        Keyword propertyRef;
        // InstanceAuditHeader properties should neither be namespace-d nor '.value' qualified, as they are not
        // InstanceValueProperties but simple native types
        if (namespace.startsWith(EntitySummaryMapping.N_CLASSIFICATIONS)) {
            // However, if they are instance headers embedded in a classification, they still need the base-level
            // classification namespace qualifier
            propertyRef = Keyword.intern(namespace, propertyName);
        } else {
            propertyRef = Keyword.intern(propertyName);
        }
        return propertyRef;
    }

    /**
     * Retrieve the Crux query condition(s) for the specified property and comparison operations.
     * @param propertyRef to compare
     * @param comparator comparison to carry out
     * @param value against which to compare
     * @param outerCriteria matching criteria inside of which this condition will exist
     * @param variable to which to compare
     * @return {@code List<IPersistentCollection>} of the conditions
     */
    protected List<IPersistentCollection> getConditionForPropertyRef(Keyword propertyRef,
                                                                     PropertyComparisonOperator comparator,
                                                                     InstancePropertyValue value,
                                                                     MatchCriteria outerCriteria,
                                                                     Symbol variable) {
        List<IPersistentCollection> propertyConditions = new ArrayList<>();
        if (comparator.equals(PropertyComparisonOperator.EQ)) {
            // For equality we can compare directly to the value
            propertyConditions.add(PersistentVector.create(DOC_ID, propertyRef, getValueForComparison(value)));
        } else if (comparator.equals(PropertyComparisonOperator.NEQ)) {
            // Similarly for inequality, just by wrapping in a NOT predicate
            List<Object> predicateComparison = new ArrayList<>();
            predicateComparison.add(NOT_OPERATOR);
            predicateComparison.add(PersistentVector.create(DOC_ID, propertyRef, getValueForComparison(value)));
            propertyConditions.add(PersistentList.create(predicateComparison));
        } else {
            // For any others, we need to translate into predicate form, which requires two pieces:
            //  [e :property variable]
            //  [(predicate variable "value")] | [(predicate #"regex" variable)
            // These two pieces need to be combined in particular ways depending on the outer criteria, see logic
            // further below for that...
            Symbol predicate = getPredicateForOperator(comparator);
            List<Object> predicateComparison = new ArrayList<>();
            predicateComparison.add(predicate);
            if (REGEX_OPERATOR.equals(predicate)) {
                // TODO: for now this treats all string comparisons as raw regexes -- this is likely to be the most complete
                //  functionality, but may also be the slowest for common scenarios like 'exact-match' but also possibly
                //  for 'contains', 'starts-with', etc.
                //  It may be worthwhile splitting out the most common scenarios for direct Clojure operations like
                //  just using the EQ approach above for exact-match, then the Clojure string predicates like
                //  clojure.string.ends-with?, clojure.string.includes? and clojure.string.starts-with? and only fall-back
                //  to the below options if the received property is a string and not one of these simple (common) regexes
                // For regexes, we need a (predicate #"value" variable) pattern
                Object compareTo = getValueForComparison(value);
                if (compareTo instanceof String) {
                    // Compile a Pattern for the regex
                    Pattern regex = Pattern.compile((String) compareTo);
                    predicateComparison.add(regex);
                    predicateComparison.add(variable);
                } else {
                    log.warn("Requested a regex-based search without providing a regex -- cannot add condition: {}", value);
                }
            } else {
                // For everything else, we need a (predicate variable value) pattern
                // Setup a predicate comparing that variable to the value (with appropriate comparison operator)
                predicateComparison.add(variable);
                predicateComparison.add(getValueForComparison(value));
            }
            // Start by wrapping everything with an 'and' predicate (only needed if outer condition is ANY (an or-join))
            if (MatchCriteria.ANY.equals(outerCriteria)) {
                // Since the variables involved across multiple conditions can be different, and an OR predicate
                // requires all of the variables to be the same, if the outer criteria is ANY we need to wrap these
                // two conditions together with an AND predicate
                // (and the calling method will further wrap this with an 'or-join')
                List<Object> predicateConditions = new ArrayList<>();
                predicateConditions.add(AND_OPERATOR);
                predicateConditions.add(PersistentVector.create(DOC_ID, propertyRef, variable));
                predicateConditions.add(PersistentVector.create(PersistentList.create(predicateComparison)));
                propertyConditions.add(PersistentList.create(predicateConditions));
            } else {
                // Otherwise (NONE and ALL) we do not need any wrapping here (calling method will wrap with a
                // 'not-join' for a NONE, but we do not need an inner AND wrapping as it is implicit for a not-join)
                propertyConditions.add(PersistentVector.create(DOC_ID, propertyRef, variable));
                propertyConditions.add(PersistentVector.create(PersistentList.create(predicateComparison)));
            }
        }
        return propertyConditions;
    }

    /**
     * Retrieve the Crux predicate for the provided comparison operation.
     * @param comparator to translate into a Crux predicate
     * @return Symbol giving the appropriate Crux predicate
     */
    protected Symbol getPredicateForOperator(PropertyComparisonOperator comparator) {
        Symbol toUse = PCO_TO_SYMBOL.getOrDefault(comparator, null);
        if (toUse == null) {
            log.warn("Unmapped comparison operator: {}", comparator);
        }
        return toUse;
    }

    /**
     * Convert the provided Egeria value into a Crux comparable form.
     * @param ipv Egeria value to translate to Crux-comparable value
     * @return Object value that Crux can compare
     */
    protected Object getValueForComparison(InstancePropertyValue ipv) {
        InstancePropertyCategory category = ipv.getInstancePropertyCategory();
        Object value = null;
        switch (category) {
            case PRIMITIVE:
                value = PrimitivePropertyValueMapping.getPrimitiveValueForComparison((PrimitivePropertyValue) ipv);
                break;
            case ENUM:
                value = EnumPropertyValueMapping.getEnumPropertyValueForComparison((EnumPropertyValue) ipv);
                break;
            case STRUCT: // TODO...
            case ARRAY: // TODO...
            case MAP: // TODO...
            case UNKNOWN:
            default:
                log.warn("Unmapped value type: {}", category);
                break;
        }
        return value;
    }

    /**
     * Translate the provided condition into the appropriate Crux representation (List for predicated-conditions, Vector
     * for any other conditions)
     * @param condition to translate
     * @return IPersistentCollection of the appropriate Crux representation
     */
    private IPersistentCollection getCruxCondition(List<Object> condition) {
        if (condition != null && !condition.isEmpty()) {
            Object first = condition.get(0);
            if (first instanceof Symbol) {
                // If the first element is a Symbol, it's an OR, OR-JOIN, AND, NOT or NOT-JOIN -- create a list
                return PersistentList.create(condition);
            } else {
                // Otherwise (ie. single condition) assume it's a Vector -- create a Vector accordingly
                return PersistentVector.create(condition);
            }
        }
        return null;
    }

    /**
     * Add the provided statuses as limiters on which results should be retrieved from the query.
     * @param limitResultsByStatus list of statuses by which to limit results
     */
    public void addStatusLimiters(List<InstanceStatus> limitResultsByStatus) {
        if (limitResultsByStatus != null && !limitResultsByStatus.isEmpty()) {
            List<IPersistentVector> statusConditions = new ArrayList<>();
            for (InstanceStatus limitByStatus : limitResultsByStatus) {
                Integer ordinal = EnumPropertyValueMapping.getOrdinalForInstanceStatus(limitByStatus);
                if (ordinal != null) {
                    statusConditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.CURRENT_STATUS, ordinal));
                }
            }
            if (!statusConditions.isEmpty()) {
                if (statusConditions.size() == 1) {
                    // If there is only one, add it directly as a condition
                    conditions.addAll(statusConditions);
                } else {
                    // Otherwise, wrap the conditions in an OR-predicate, and add that list to the conditions
                    List<Object> wrapped = new ArrayList<>();
                    wrapped.add(OR_OPERATOR);
                    wrapped.addAll(statusConditions);
                    conditions.add(PersistentList.create(wrapped));
                }
            }
        }
    }

    /**
     * Add the sequencing information onto the query. (If both are null, will default to sorting by GUID.)
     * @param sequencingOrder by which to sequence the results
     * @param sequencingProperty by which to sequence the results (required if sorting by property, otherwise ignored)
     * @param namespace by which to qualify the sorting property (required if sorting by property, otherwise ignored)
     * @param typeNames of all of the types we are including in the search (required if sorting by property, otherwise ignored)
     * @param repositoryHelper through which we can lookup type information and properties (required if sorting by property, otherwise ignored)
     * @param repositoryName of the repository (for logging)
     */
    public void addSequencing(SequencingOrder sequencingOrder,
                              String sequencingProperty,
                              String namespace,
                              Set<String> typeNames,
                              OMRSRepositoryHelper repositoryHelper,
                              String repositoryName) {
        Set<Keyword> qualifiedSortProperties = null;
        if (sequencingProperty != null) {
            // Translate the provided sequencingProperty name into all of its possible appropriate property name
            // references (depends on the type limiting used for the search)
            qualifiedSortProperties = InstancePropertyValueMapping.getNamesForProperty(repositoryName, repositoryHelper, sequencingProperty, namespace, typeNames, null);
        }
        if (sequencingOrder == null) {
            // Default to sorting by GUID, if no sorting is defined (for consistent result ordering, paging, etc)
            sequencingOrder = SequencingOrder.GUID;
        }
        // Note: for sorting by anything other than document ID we need to ensure we also add the
        // element to the conditions and sequence (unless there already as part of another search criteria), hence the
        // 'addFindElement' logic.
        switch (sequencingOrder) {
            case LAST_UPDATE_OLDEST:
                addFindElement(UPDATE_TIME);
                conditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.UPDATE_TIME, UPDATE_TIME));
                sequencing.add(PersistentVector.create(UPDATE_TIME, SORT_ASCENDING));
                break;
            case LAST_UPDATE_RECENT:
                addFindElement(UPDATE_TIME);
                conditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.UPDATE_TIME, UPDATE_TIME));
                sequencing.add(PersistentVector.create(UPDATE_TIME, SORT_DESCENDING));
                break;
            case CREATION_DATE_OLDEST:
                addFindElement(CREATE_TIME);
                conditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.CREATE_TIME, CREATE_TIME));
                sequencing.add(PersistentVector.create(CREATE_TIME, SORT_ASCENDING));
                break;
            case CREATION_DATE_RECENT:
                addFindElement(CREATE_TIME);
                conditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.CREATE_TIME, CREATE_TIME));
                sequencing.add(PersistentVector.create(CREATE_TIME, SORT_DESCENDING));
                break;
            case PROPERTY_ASCENDING:
                if (qualifiedSortProperties == null || qualifiedSortProperties.isEmpty()) {
                    log.warn("Requested sort by property, but no valid property was provided ({}) given type limiters ({}) -- skipping sort.", sequencingProperty, typeNames);
                } else {
                    addPropertyBasedSorting(qualifiedSortProperties, SORT_ASCENDING);
                }
                break;
            case PROPERTY_DESCENDING:
                if (qualifiedSortProperties == null || qualifiedSortProperties.isEmpty()) {
                    log.warn("Requested sort by property, but no valid property was provided ({}) given type limiters ({}) -- skipping sort.", sequencingProperty, typeNames);
                } else {
                    addPropertyBasedSorting(qualifiedSortProperties, SORT_DESCENDING);
                }
                break;
            case ANY:
            case GUID:
            default:
                sequencing.add(PersistentVector.create(DOC_ID, SORT_ASCENDING));
                break;
        }
    }

    /**
     * Add the necessary conditions for sorting based on a property: somewhat complex as we need to ensure that the
     * property being used in the sort is included in the search results themselves, and given the qualification of
     * property names this could mean several different properties we ultimately need to attempt to sort by, and hence
     * this separate method.
     * @param qualifiedSortProperties the set of properties by which we will sort
     * @param order indicating ascending or descending
     */
    private void addPropertyBasedSorting(Set<Keyword> qualifiedSortProperties, Keyword order) {
        addFindElement(SORT_PROPERTY);
        if (qualifiedSortProperties.size() == 1) {
            // If there is only a single condition for sorting, just add it directly
            for (Keyword propertyRef : qualifiedSortProperties) {
                conditions.add(PersistentVector.create(DOC_ID, propertyRef, SORT_PROPERTY));
            }
        } else {
            // Otherwise, we need to combine the conditions together as an or-join (use the first result against any
            // of them for a given instance for sorting that instance)
            List<Object> orJoinConditions = new ArrayList<>();
            orJoinConditions.add(OR_JOIN);
            orJoinConditions.add(PersistentVector.create(SORT_PROPERTY));
            for (Keyword propertyRef : qualifiedSortProperties) {
                orJoinConditions.add(PersistentVector.create(DOC_ID, propertyRef, SORT_PROPERTY));
            }
            conditions.add(PersistentList.create(orJoinConditions));
        }
        sequencing.add(PersistentVector.create(SORT_PROPERTY, order));
    }

    /**
     * Add the paging information onto the query.
     * @param fromElement starting element for the results
     * @param pageSize maximum number of results
     */
    public void addPaging(int fromElement, int pageSize) {
        offset = fromElement;
        if (pageSize > 0) {
            // If the pageSize is 0, then we would return no results (no point in searching), so we will only bother
            // to override the pageSize if a value greater than 0 has been provided. (Some of the REST requests classes
            // in Egeria core default the pageSize to 0 if it has not been specified explicitly, which does not really
            // make any sense: we will instead use the overall default page size setup as part of the query constructor).
            limit = pageSize;
        }
    }

    /**
     * Retrieve the query object, as ready-to-be-submitted to Crux API's query method.
     * @return IPersistentMap containing the query
     */
    public IPersistentMap getQuery() {
        // Add the elements to be found:  :find [ e ... ]
        query = query.assoc(Keyword.intern("find"), PersistentVector.create(findElements));
        // Add the conditions to the query:  :where [[ ... condition ...], [ ... condition ... ], ... ]
        query = query.assoc(Keyword.intern("where"), PersistentVector.create(conditions));
        // Add the sequencing information to the query:  :order-by [[ ... ]]
        query = query.assoc(Keyword.intern("order-by"), PersistentVector.create(sequencing));
        // Add the limit information to the query:  :limit n
        query = query.assoc(Keyword.intern("limit"), limit);
        // Add the offset information to the query:  :offset n
        query = query.assoc(Keyword.intern("offset"), offset);
        return query;
    }

    /**
     * Add the specified symbol to the list of those that are discovered by the search conditions (if not already in
     * the list)
     * @param element to add (if not already in the list)
     */
    private void addFindElement(Symbol element) {
        if (!findElements.contains(element)) {
            findElements.add(element);
        }
    }

    /**
     * Retrieve a condition that will ensure no results are returned by a query.
     * @return {@code List<IPersistentCollection>}
     */
    private List<IPersistentCollection> getNoResultsCondition() {
        List<IPersistentCollection> conditions = new ArrayList<>();
        conditions.add(PersistentVector.create(DOC_ID, InstanceAuditHeaderMapping.TYPE_DEF_GUID, "NON_EXISTENT_TO_FORCE_NO_RESULTS"));
        return conditions;
    }

}
