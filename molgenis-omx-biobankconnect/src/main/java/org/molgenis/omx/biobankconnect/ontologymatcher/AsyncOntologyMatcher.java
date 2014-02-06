package org.molgenis.omx.biobankconnect.ontologymatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.elasticsearch.common.collect.Iterables;
import org.elasticsearch.common.collect.Lists;
import org.molgenis.MolgenisFieldTypes;
import org.molgenis.data.DataConverter;
import org.molgenis.data.DataService;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.Query;
import org.molgenis.data.QueryRule;
import org.molgenis.data.QueryRule.Operator;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.omx.biobankconnect.utils.NGramMatchingModel;
import org.molgenis.omx.biobankconnect.utils.StoreMappingRepository;
import org.molgenis.omx.biobankconnect.wizard.CurrentUserStatus;
import org.molgenis.omx.biobankconnect.wizard.CurrentUserStatus.STAGE;
import org.molgenis.omx.observ.DataSet;
import org.molgenis.omx.observ.ObservableFeature;
import org.molgenis.omx.observ.ObservationSet;
import org.molgenis.omx.observ.ObservedValue;
import org.molgenis.omx.observ.Protocol;
import org.molgenis.omx.observ.target.OntologyTerm;
import org.molgenis.omx.observ.value.BoolValue;
import org.molgenis.omx.observ.value.DecimalValue;
import org.molgenis.omx.observ.value.IntValue;
import org.molgenis.omx.observ.value.StringValue;
import org.molgenis.search.Hit;
import org.molgenis.search.MultiSearchRequest;
import org.molgenis.search.SearchRequest;
import org.molgenis.search.SearchResult;
import org.molgenis.search.SearchService;
import org.molgenis.security.runas.RunAsSystem;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.tartarus.snowball.ext.PorterStemmer;

public class AsyncOntologyMatcher implements OntologyMatcher, InitializingBean
{
	private static final Logger logger = Logger.getLogger(AsyncOntologyMatcher.class);
	private static final String PROTOCOL_IDENTIFIER = "store_mapping";
	private static final String STORE_MAPPING_FEATURE = "store_mapping_feature";
	private static final String STORE_MAPPING_MAPPED_FEATURE = "store_mapping_mapped_feature";
	private static final String STORE_MAPPING_CONFIRM_MAPPING = "store_mapping_confirm_mapping";
	private static final String STORE_MAPPING_SCORE = "store_mapping_score";
	private static final String STORE_MAPPING_ALGORITHM_SCRIPT = "store_mapping_algorithm_script";
	private static final String CATALOGUE_PREFIX = "protocolTree-";
	private static final String FEATURE_CATEGORY = "featureCategory-";
	private static final String FIELD_DESCRIPTION_STOPWORDS = "descriptionStopwords";
	private static final String FIELD_BOOST_ONTOLOGYTERM = "boostOntologyTerms";
	private static final String ONTOLOGY_IRI = "ontologyIRI";
	private static final String ONTOLOGY_LABEL = "ontologyLabel";
	private static final String OBSERVATION_SET = "observation_set";
	private static final String ONTOLOGYTERM_SYNONYM = "ontologyTermSynonym";
	private static final String ONTOLOGY_TERM = "ontologyTerm";
	private static final String ONTOLOGY_TERM_IRI = "ontologyTermIRI";
	private static final String ALTERNATIVE_DEFINITION = "alternativeDefinition";
	private static final String NODE_PATH = "nodePath";
	private static final String ENTITY_ID = "id";
	private static final String LUCENE_SCORE = "score";
	private static final String ENTITY_TYPE = "type";
	private static final AtomicInteger runningProcesses = new AtomicInteger();
	private static final PorterStemmer stemmer = new PorterStemmer();

	@Autowired
	private DataService dataService;

	@Autowired
	private CurrentUserStatus currentUserStatus;

	private SearchService searchService;

	@Autowired
	public void setSearchService(SearchService searchService)
	{
		this.searchService = searchService;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		if (searchService == null) throw new IllegalArgumentException("Missing bean of type SearchService");
	}

	@Override
	public boolean isRunning()
	{
		if (runningProcesses.get() != 0) return true;
		return false;
	}

	@Override
	public Integer matchPercentage(String currentUserName)
	{
		return currentUserStatus.getPercentageOfProcessForUser(currentUserName);
	}

	@Override
	public void deleteDocumentByIds(String documentType, List<String> documentIds)
	{
		searchService.deleteDocumentByIds(documentType, documentIds);
	}

	@Override
	@RunAsSystem
	@Async
	@Transactional
	public void match(String userName, Integer selectedDataSet, List<Integer> dataSetsToMatch, Integer featureId)
	{
		runningProcesses.incrementAndGet();
		currentUserStatus.setUserIsRunning(userName, true);
		dataSetsToMatch.remove(selectedDataSet);
		List<ObservationSet> listOfNewObservationSets = new ArrayList<ObservationSet>();
		List<ObservedValue> listOfNewObservedValues = new ArrayList<ObservedValue>();
		Map<String, List<ObservedValue>> observationValuesPerDataSet = new HashMap<String, List<ObservedValue>>();
		try
		{
			QueryImpl q = new QueryImpl();
			q.pageSize(100001);

			if (featureId == null)
			{
				q.addRule(new QueryRule(ENTITY_TYPE, Operator.SEARCH, ObservableFeature.class.getSimpleName()
						.toLowerCase()));
			}
			else
			{
				q.addRule(new QueryRule(ENTITY_ID, Operator.EQUALS, featureId));
			}

			SearchResult result = searchService.search(new SearchRequest(CATALOGUE_PREFIX + selectedDataSet, q, null));

			currentUserStatus.setUserCurrentStage(userName, STAGE.DeleteMapping);
			preprocessing(userName, featureId, selectedDataSet, dataSetsToMatch);

			currentUserStatus.setUserCurrentStage(userName, STAGE.CreateMapping);
			currentUserStatus.setUserTotalNumberOfQueries(userName, result.getTotalHitCount());

			for (Hit hit : result.getSearchHits())
			{
				Map<String, Object> columnValueMap = hit.getColumnValueMap();
				Integer id = DataConverter.toInt(columnValueMap.get(ObservableFeature.ID));
				ObservableFeature feature = dataService.findOne(ObservableFeature.ENTITY_NAME, id,
						ObservableFeature.class);
				if (feature != null)
				{
					Set<String> boostedOntologyTermUris = new HashSet<String>();
					for (String ontolgoyTermUri : columnValueMap.get(FIELD_BOOST_ONTOLOGYTERM).toString().split(","))
					{
						boostedOntologyTermUris.add(ontolgoyTermUri);
					}
					String description = feature.getDescription() == null || feature.getDescription().isEmpty() ? feature
							.getName() : feature.getDescription();
					description = description.replaceAll("[^a-zA-Z0-9 ]", " ");
					List<OntologyTerm> definitions = feature.getDefinitions();

					List<QueryRule> rules = new ArrayList<QueryRule>();
					if (definitions != null && definitions.size() > 0)
					{
						Map<String, OntologyTermContainer> ontologyTermContainers = collectOntologyTermInfo(
								definitions, boostedOntologyTermUris);
						rules.addAll(makeQueryForOntologyTerms(createQueryRules(description, ontologyTermContainers)));

						for (Map<Integer, List<BoostTermContainer>> alternativeDefinition : addAlternativeDefinition(ontologyTermContainers))
						{
							QueryRule queryRule = new QueryRule(makeQueryForOntologyTerms(alternativeDefinition));
							queryRule.setOperator(Operator.DIS_MAX);
							queryRule.setValue(0.6);
							rules.add(queryRule);
						}
					}
					else rules.add(new QueryRule(FIELD_DESCRIPTION_STOPWORDS, Operator.SEARCH, description));

					QueryRule finalQueryRule = new QueryRule(rules);
					finalQueryRule.setOperator(Operator.DIS_MAX);

					QueryImpl finalQuery = new QueryImpl();
					finalQuery.addRule(finalQueryRule);
					Set<Integer> mappedFeatureIds = new HashSet<Integer>();

					for (Integer dataSetId : dataSetsToMatch)
					{
						String dataSetIdentifier = createMappingDataSetIdentifier(userName, selectedDataSet, dataSetId);
						if (featureId != null) observationValuesPerDataSet.put(dataSetIdentifier,
								new ArrayList<ObservedValue>());
						Iterator<Hit> mappedFeatureHits = searchDisMaxQuery(dataSetId.toString(), finalQuery)
								.iterator();
						while (mappedFeatureHits.hasNext())
						{
							Hit mappedFeatureHit = mappedFeatureHits.next();
							Map<String, Object> columValueMap = mappedFeatureHit.getColumnValueMap();
							Integer mappedFeatureId = Integer.parseInt(columValueMap.get(ENTITY_ID).toString());
							Double score = Double.parseDouble(columValueMap.get(LUCENE_SCORE).toString());
							if (!mappedFeatureIds.contains(mappedFeatureId))
							{
								mappedFeatureIds.add(mappedFeatureId);

								ObservationSet observation = new ObservationSet();
								observation.setIdentifier(userName + "-" + feature.getId() + "-" + mappedFeatureId
										+ "-identifier");

								DataSet ds = dataService.findOne(DataSet.ENTITY_NAME,
										new QueryImpl().eq(DataSet.IDENTIFIER, dataSetIdentifier), DataSet.class);

								observation.setPartOfDataSet(ds);
								listOfNewObservationSets.add(observation);

								IntValue xrefForFeature = new IntValue();
								xrefForFeature.setValue(feature.getId());
								dataService.add(IntValue.ENTITY_NAME, xrefForFeature);

								ObservedValue valueForFeature = new ObservedValue();
								valueForFeature.setObservationSet(observation);
								ObservableFeature smf = dataService.findOne(ObservableFeature.ENTITY_NAME,
										new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_FEATURE),
										ObservableFeature.class);
								valueForFeature.setFeature(smf);
								valueForFeature.setValue(xrefForFeature);
								listOfNewObservedValues.add(valueForFeature);
								if (featureId != null) observationValuesPerDataSet.get(dataSetIdentifier).add(
										valueForFeature);

								IntValue xrefForMappedFeature = new IntValue();
								xrefForMappedFeature.setValue(mappedFeatureId);
								dataService.add(IntValue.ENTITY_NAME, xrefForMappedFeature);

								ObservedValue valueForMappedFeature = new ObservedValue();
								ObservableFeature smmf = dataService.findOne(ObservableFeature.ENTITY_NAME,
										new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_MAPPED_FEATURE),
										ObservableFeature.class);
								valueForMappedFeature.setFeature(smmf);
								valueForMappedFeature.setObservationSet(observation);
								valueForMappedFeature.setValue(xrefForMappedFeature);
								listOfNewObservedValues.add(valueForMappedFeature);
								if (featureId != null) observationValuesPerDataSet.get(dataSetIdentifier).add(
										valueForMappedFeature);

								DecimalValue decimalForScore = new DecimalValue();
								decimalForScore.setValue(score);
								dataService.add(DecimalValue.ENTITY_NAME, decimalForScore);

								ObservedValue valueForMappedFeatureScore = new ObservedValue();
								ObservableFeature smc = dataService.findOne(ObservableFeature.ENTITY_NAME,
										new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_SCORE),
										ObservableFeature.class);
								valueForMappedFeatureScore.setFeature(smc);
								valueForMappedFeatureScore.setObservationSet(observation);
								valueForMappedFeatureScore.setValue(decimalForScore);
								listOfNewObservedValues.add(valueForMappedFeatureScore);
								if (featureId != null) observationValuesPerDataSet.get(dataSetIdentifier).add(
										valueForMappedFeatureScore);

								BoolValue boolValue = new BoolValue();
								boolValue.setValue(false);
								dataService.add(BoolValue.ENTITY_NAME, boolValue);

								ObservedValue confirmMappingValue = new ObservedValue();
								ObservableFeature scm = dataService
										.findOne(ObservableFeature.ENTITY_NAME, new QueryImpl().eq(
												ObservableFeature.IDENTIFIER, STORE_MAPPING_CONFIRM_MAPPING),
												ObservableFeature.class);
								confirmMappingValue.setFeature(scm);
								confirmMappingValue.setObservationSet(observation);
								confirmMappingValue.setValue(boolValue);
								listOfNewObservedValues.add(confirmMappingValue);
								if (featureId != null) observationValuesPerDataSet.get(dataSetIdentifier).add(
										confirmMappingValue);
							}
						}
					}
					currentUserStatus.incrementFinishedNumberOfQueries(userName);
				}
			}

			dataService.add(ObservationSet.ENTITY_NAME, listOfNewObservationSets);
			Set<Integer> processedObservationSets = new HashSet<Integer>();
			List<ObservedValue> valuesForObservationSets = new ArrayList<ObservedValue>();
			for (ObservedValue value : listOfNewObservedValues)
			{
				ObservationSet observationSet = value.getObservationSet();
				Integer observationSetId = observationSet.getId();
				if (!processedObservationSets.contains(observationSetId))
				{
					processedObservationSets.add(observationSetId);
					IntValue observationSetIntValue = new IntValue();
					observationSetIntValue.setValue(observationSetId);
					dataService.add(IntValue.ENTITY_NAME, observationSetIntValue);
					ObservedValue valueForObservationSet = new ObservedValue();
					ObservableFeature observationSetFeature = dataService.findOne(ObservableFeature.ENTITY_NAME,
							new QueryImpl().eq(ObservableFeature.IDENTIFIER, OBSERVATION_SET), ObservableFeature.class);
					valueForObservationSet.setFeature(observationSetFeature);
					valueForObservationSet.setObservationSet(observationSet);
					valueForObservationSet.setValue(observationSetIntValue);
					valuesForObservationSets.add(valueForObservationSet);
					if (observationValuesPerDataSet.containsKey(observationSet.getPartOfDataSet().getIdentifier())) observationValuesPerDataSet
							.get(observationSet.getPartOfDataSet().getIdentifier()).add(valueForObservationSet);
				}
			}
			listOfNewObservedValues.addAll(valuesForObservationSets);
			dataService.add(ObservedValue.ENTITY_NAME, listOfNewObservedValues);

			currentUserStatus.setUserCurrentStage(userName, STAGE.StoreMapping);
			currentUserStatus.setUserTotalNumberOfQueries(userName, (long) dataSetsToMatch.size());
			if (featureId != null)
			{
				for (Entry<String, List<ObservedValue>> entry : observationValuesPerDataSet.entrySet())
				{
					DataSet dataSet = dataService.findOne(DataSet.ENTITY_NAME,
							new QueryImpl().eq(DataSet.IDENTIFIER, entry.getKey()), DataSet.class);
					searchService.updateRepositoryIndex(new StoreMappingRepository(dataSet, entry.getValue(),
							dataService));
					currentUserStatus.incrementFinishedNumberOfQueries(userName);
				}
			}
			else
			{
				for (Integer catalogueId : dataSetsToMatch)
				{
					StringBuilder dataSetIdentifier = new StringBuilder();
					dataSetIdentifier.append(userName).append('-').append(selectedDataSet).append('-')
							.append(catalogueId);
					DataSet dataSet = dataService.findOne(DataSet.ENTITY_NAME,
							new QueryImpl().eq(DataSet.IDENTIFIER, dataSetIdentifier), DataSet.class);
					searchService.indexRepository(new StoreMappingRepository(dataSet, dataService));
					currentUserStatus.incrementFinishedNumberOfQueries(userName);
				}
			}
		}
		catch (Exception e)
		{
			logger.error("Exception the matching process has failed!", e);
		}
		finally
		{
			runningProcesses.decrementAndGet();
			currentUserStatus.setUserIsRunning(userName, false);
		}
	}

	@Override
	@Transactional
	public SearchResult generateMapping(String userName, Integer featureId, Integer targetDataSet, Integer sourceDataSet)
	{
		QueryImpl query = new QueryImpl();
		query.pageSize(100000);
		query.addRule(new QueryRule(ENTITY_ID, Operator.EQUALS, featureId));
		SearchResult result = searchService.search(new SearchRequest(CATALOGUE_PREFIX + targetDataSet, query, null));
		List<Hit> searchHits = result.getSearchHits();
		if (searchHits.size() > 0)
		{
			Hit hit = searchHits.get(0);
			Map<String, Object> columnValueMap = hit.getColumnValueMap();
			ObservableFeature feature = dataService.findOne(ObservableFeature.ENTITY_NAME, featureId,
					ObservableFeature.class);
			if (feature != null)
			{
				Set<String> boostedOntologyTermUris = new HashSet<String>();
				for (String ontolgoyTermUri : columnValueMap.get(FIELD_BOOST_ONTOLOGYTERM).toString().split(","))
				{
					boostedOntologyTermUris.add(ontolgoyTermUri);
				}
				String description = feature.getDescription() == null || feature.getDescription().isEmpty() ? feature
						.getName() : feature.getDescription();
				description = description.replaceAll("[^a-zA-Z0-9 ]", " ");
				List<OntologyTerm> definitions = feature.getDefinitions();

				List<QueryRule> rules = new ArrayList<QueryRule>();
				if (definitions != null && definitions.size() > 0)
				{
					Map<String, OntologyTermContainer> ontologyTermContainers = collectOntologyTermInfo(definitions,
							boostedOntologyTermUris);
					rules.addAll(makeQueryForOntologyTerms(createQueryRules(description, ontologyTermContainers)));
					for (Map<Integer, List<BoostTermContainer>> alternativeDefinition : addAlternativeDefinition(ontologyTermContainers))
					{
						QueryRule queryRule = new QueryRule(makeQueryForOntologyTerms(alternativeDefinition));
						queryRule.setOperator(Operator.DIS_MAX);
						queryRule.setValue(0.6);
						rules.add(queryRule);
					}
				}
				else
				{
					rules.add(new QueryRule(FIELD_DESCRIPTION_STOPWORDS, Operator.SEARCH, description));
				}

				QueryRule finalQueryRule = new QueryRule(rules);
				finalQueryRule.setOperator(Operator.DIS_MAX);

				QueryImpl finalQuery = new QueryImpl();
				finalQuery.addRule(finalQueryRule);
				return searchDisMaxQuery(sourceDataSet.toString(), finalQuery);
			}
		}
		return new SearchResult(0, Collections.<Hit> emptyList());
	}

	private void preprocessing(String userName, Integer featureId, Integer targetDataSet, List<Integer> sourceDataSets)
	{
		List<String> dataSetsForMapping = new ArrayList<String>();
		for (Integer sourceDataSet : sourceDataSets)
		{
			dataSetsForMapping.add(createMappingDataSetIdentifier(userName, targetDataSet, sourceDataSet));
		}
		if (featureId == null)
		{
			createMappingStore(userName, targetDataSet, sourceDataSets);
			deleteExistingRecords(userName, dataSetsForMapping);
		}
		else removeExistingMappings(featureId, dataSetsForMapping);
	}

	private void deleteExistingRecords(String userName, List<String> dataSetsForMapping)
	{
		currentUserStatus.setUserTotalNumberOfQueries(userName, (long) dataSetsForMapping.size());
		Iterable<DataSet> dataSets = dataService.findAll(DataSet.ENTITY_NAME,
				new QueryImpl().in(DataSet.IDENTIFIER, dataSetsForMapping), DataSet.class);
		for (DataSet dataSet : dataSets)
		{
			Iterable<ObservationSet> listOfObservationSets = dataService.findAll(ObservationSet.ENTITY_NAME,
					new QueryImpl().eq(ObservationSet.PARTOFDATASET, dataSet), ObservationSet.class);

			if (Iterables.size(listOfObservationSets) > 0)
			{
				Iterable<ObservedValue> listOfObservedValues = dataService.findAll(ObservedValue.ENTITY_NAME,
						new QueryImpl().in(ObservedValue.OBSERVATIONSET, Lists.newArrayList(listOfObservationSets)),
						ObservedValue.class);

				if (Iterables.size(listOfObservedValues) > 0) dataService.delete(ObservedValue.ENTITY_NAME,
						listOfObservedValues);
				dataService.delete(ObservationSet.ENTITY_NAME, listOfObservationSets);
			}
			currentUserStatus.incrementFinishedNumberOfQueries(userName);
		}
	}

	private void removeExistingMappings(Integer featureId, List<String> dataSetsForMapping)
	{
		List<Integer> observationSets = new ArrayList<Integer>();
		for (String dataSet : dataSetsForMapping)
		{
			QueryImpl q = new QueryImpl();
			q.pageSize(100000);
			q.addRule(new QueryRule(STORE_MAPPING_FEATURE, Operator.EQUALS, featureId));

			SearchRequest request = new SearchRequest(dataSet, q, null);
			SearchResult searchResult = searchService.search(request);

			List<String> indexIds = new ArrayList<String>();
			for (Hit hit : searchResult.getSearchHits())
			{
				Map<String, Object> columnValueMap = hit.getColumnValueMap();
				indexIds.add(hit.getId());
				observationSets.add(Integer.parseInt(columnValueMap.get(OBSERVATION_SET).toString()));
			}
			searchService.deleteDocumentByIds(dataSet, indexIds);
		}

		if (observationSets.size() > 0)
		{
			Iterable<ObservationSet> existingObservationSets = dataService.findAll(ObservationSet.ENTITY_NAME,
					new QueryImpl().in(ObservationSet.ID, observationSets), ObservationSet.class);

			Iterable<ObservedValue> existingObservedValues = dataService.findAll(ObservedValue.ENTITY_NAME,
					new QueryImpl().in(ObservedValue.OBSERVATIONSET, Lists.newArrayList(existingObservationSets)),
					ObservedValue.class);

			if (Iterables.size(existingObservedValues) > 0) dataService.delete(ObservedValue.ENTITY_NAME,
					existingObservedValues);

			if (Iterables.size(existingObservationSets) > 0) dataService.delete(ObservationSet.ENTITY_NAME,
					existingObservationSets);
		}
	}

	private List<QueryRule> makeQueryForOntologyTerms(Map<Integer, List<BoostTermContainer>> position)
	{
		boolean boostDesccription = false;
		List<QueryRule> allQueries = new ArrayList<QueryRule>();
		List<QueryRule> queryRules = new ArrayList<QueryRule>();
		Map<Integer, Boolean> boostIndex = new HashMap<Integer, Boolean>();
		for (Entry<Integer, List<BoostTermContainer>> entry : position.entrySet())
		{
			Integer index = entry.getKey();
			if (index >= 0)
			{
				boolean boost = false;
				List<QueryRule> subQueries = new ArrayList<QueryRule>();
				for (BoostTermContainer boostTermContainer : entry.getValue())
				{
					List<QueryRule> rules = new ArrayList<QueryRule>();
					for (String term : boostTermContainer.getTerms())
					{
						rules.add(new QueryRule(FIELD_DESCRIPTION_STOPWORDS, Operator.EQUALS, term.trim()));
						rules.add(new QueryRule(ObservableFeature.DESCRIPTION, Operator.EQUALS, term.trim()));
					}
					if (!boost) boost = boostTermContainer.isBoost();
					QueryRule queryRule = new QueryRule(rules);
					queryRule.setOperator(Operator.DIS_MAX);
					queryRule.setValue(boostTermContainer.isBoost() ? 10 : null);
					subQueries.add(queryRule);
				}
				if (!boostIndex.containsKey(index))
				{
					boostIndex.put(index, boost);
				}
				else if (!boostIndex.get(index))
				{
					boostIndex.put(index, boost);
				}
				QueryRule queryRule = null;
				if (subQueries.size() == 1)
				{
					queryRule = subQueries.get(0);
				}
				else
				{
					queryRule = new QueryRule(subQueries);
					queryRule.setOperator(Operator.DIS_MAX);
					queryRule.setValue(boost ? 10 : null);
				}
				queryRules.add(queryRule);

				if (!boostDesccription) boostDesccription = boost;
			}
			else if (index == -1)
			{
				for (BoostTermContainer boostTermContainer : entry.getValue())
				{
					if (boostTermContainer.getTerms().size() > 0)
					{
						List<QueryRule> rules = new ArrayList<QueryRule>();
						for (String term : boostTermContainer.getTerms())
						{
							if (!term.isEmpty())
							{
								rules.add(new QueryRule(FIELD_DESCRIPTION_STOPWORDS, Operator.EQUALS, term.trim()));
								rules.add(new QueryRule(ObservableFeature.DESCRIPTION, Operator.EQUALS, term.trim()));
							}
						}
						QueryRule queryRule = new QueryRule(rules);
						queryRule.setOperator(Operator.DIS_MAX);
						queryRule.setValue(boostTermContainer.isBoost() ? 10 : null);
						allQueries.add(queryRule);
						if (!boostDesccription) boostDesccription = boostTermContainer.isBoost();
					}
				}
			}
			else
			{
				for (BoostTermContainer boostTermContainer : entry.getValue())
				{
					StringBuilder boostedSynonym = new StringBuilder();
					List<QueryRule> rules = new ArrayList<QueryRule>();
					for (String term : boostTermContainer.getTerms())
					{
						if (!term.isEmpty())
						{
							int count = 0;
							if (boostDesccription)
							{
								for (String eachToken : term.split(" +"))
								{
									if (boostedSynonym.length() != 0) boostedSynonym.append(' ');
									boostedSynonym.append(eachToken);
									if (boostIndex.containsKey(count) && boostIndex.get(count)) boostedSynonym.append(
											'^').append(10);
									count++;
								}
							}
							else boostedSynonym.append(term);

							rules.add(new QueryRule(FIELD_DESCRIPTION_STOPWORDS, Operator.EQUALS, boostedSynonym
									.toString()));
							rules.add(new QueryRule(ObservableFeature.DESCRIPTION, Operator.EQUALS, boostedSynonym
									.toString()));
						}
					}
					QueryRule queryRule = new QueryRule(rules);
					queryRule.setOperator(Operator.DIS_MAX);
					queryRule.setValue(boostDesccription || boostTermContainer.isBoost() ? 1.5 : null);
					allQueries.add(queryRule);
				}
			}
		}

		if (queryRules.size() > 0)
		{
			QueryRule combinedQuery = null;
			if (queryRules.size() != 1)
			{
				combinedQuery = new QueryRule(queryRules);
				combinedQuery.setOperator(Operator.SHOULD);
				allQueries.add(combinedQuery);
			}
			else allQueries.add(queryRules.get(0));
		}
		return allQueries;
	}

	private SearchResult searchDisMaxQuery(String dataSetId, Query q)
	{
		SearchResult result = null;
		try
		{
			q.pageSize(50);
			MultiSearchRequest request = new MultiSearchRequest(Arrays.asList(CATALOGUE_PREFIX + dataSetId,
					FEATURE_CATEGORY + dataSetId), q, null);
			result = searchService.multiSearch(request);
		}
		catch (Exception e)
		{
			result = new SearchResult(e.getMessage());
			logger.error("Exception failed to search the request " + result, e);
		}
		return result;
	}

	private Map<String, OntologyTermContainer> collectOntologyTermInfo(List<OntologyTerm> definitions,
			Set<String> boostedOntologyTermUris)
	{
		Map<String, String> validOntologyTerm = new HashMap<String, String>();
		Map<String, OntologyTermContainer> totalHits = new HashMap<String, OntologyTermContainer>();

		List<QueryRule> rules = new ArrayList<QueryRule>();
		for (OntologyTerm ot : definitions)
		{
			if (rules.size() != 0) rules.add(new QueryRule(Operator.OR));
			rules.add(new QueryRule(ONTOLOGY_TERM_IRI, Operator.EQUALS, ot.getTermAccession()));
			validOntologyTerm.put(ot.getTermAccession(), ot.getName());
		}

		QueryImpl query = new QueryImpl();
		query.pageSize(10000);
		for (QueryRule rule : rules)
		{
			query.addRule(rule);
		}

		SearchRequest request = new SearchRequest(null, query, null);
		SearchResult result = searchService.search(request);
		Iterator<Hit> iterator = result.iterator();

		while (iterator.hasNext())
		{
			Hit hit = iterator.next();
			Map<String, Object> columnValueMap = hit.getColumnValueMap();
			String ontologyIRI = columnValueMap.get(ONTOLOGY_IRI).toString();
			String ontologyTermUri = columnValueMap.get(ONTOLOGY_TERM_IRI).toString();
			String ontologyTermName = columnValueMap.get(ONTOLOGY_LABEL) + ":"
					+ columnValueMap.get(ONTOLOGYTERM_SYNONYM).toString();
			Boolean boost = boostedOntologyTermUris.contains(ontologyTermUri);

			if (validOntologyTerm.containsKey(ontologyTermUri)
					&& validOntologyTerm.get(ontologyTermUri).equalsIgnoreCase(ontologyTermName))
			{
				String alternativeDefinitions = columnValueMap.get(ALTERNATIVE_DEFINITION) == null ? StringUtils.EMPTY : columnValueMap
						.get(ALTERNATIVE_DEFINITION).toString();
				String nodePath = columnValueMap.get(NODE_PATH).toString();
				if (!totalHits.containsKey(ontologyIRI)) totalHits.put(ontologyIRI, new OntologyTermContainer(
						ontologyIRI));
				totalHits.get(ontologyIRI).getAllPaths().put(nodePath, boost);
				totalHits.get(ontologyIRI).getAlternativeDefinitions().put(nodePath, alternativeDefinitions);
				totalHits.get(ontologyIRI).getSelectedOntologyTerms().add(hit.getId());
			}
		}
		return totalHits;
	}

	private List<Map<Integer, List<BoostTermContainer>>> addAlternativeDefinition(
			Map<String, OntologyTermContainer> ontologyTermContainers)
	{
		List<Map<Integer, List<BoostTermContainer>>> positions = new ArrayList<Map<Integer, List<BoostTermContainer>>>();
		for (Entry<String, OntologyTermContainer> entry : ontologyTermContainers.entrySet())
		{
			String ontologyIRI = entry.getKey();
			OntologyTermContainer container = entry.getValue();
			if (container.getAlternativeDefinitions().size() > 0)
			{
				for (Entry<String, String> entryForAlterDefinition : container.getAlternativeDefinitions().entrySet())
				{
					String definitionString = entryForAlterDefinition.getValue();
					if (!definitionString.isEmpty())
					{
						Boolean boost = container.getAllPaths().get(entryForAlterDefinition.getKey());
						for (String definition : definitionString.split("&&&"))
						{
							Map<String, OntologyTermContainer> totalHits = new HashMap<String, OntologyTermContainer>();
							Set<String> ontologyTerms = new HashSet<String>();

							List<QueryRule> rules = new ArrayList<QueryRule>();
							for (String relatedOntologyTermUri : definition.split(","))
							{
								if (rules.size() != 0) rules.add(new QueryRule(Operator.OR));
								rules.add(new QueryRule(ONTOLOGY_TERM_IRI, Operator.EQUALS, relatedOntologyTermUri));
							}

							QueryImpl q = new QueryImpl();
							q.pageSize(10000);
							for (QueryRule rule : rules)
							{
								q.addRule(rule);
							}

							SearchRequest request = new SearchRequest(null, q, null);
							SearchResult result = searchService.search(request);

							Iterator<Hit> iterator = result.iterator();
							while (iterator.hasNext())
							{
								Hit hit = iterator.next();
								Map<String, Object> columnValueMap = hit.getColumnValueMap();
								if (columnValueMap.get(ONTOLOGY_IRI).toString().equals(ontologyIRI))
								{
									String nodePath = columnValueMap.get(NODE_PATH).toString();
									String ontologyTerm = columnValueMap.get(ONTOLOGY_TERM).toString().trim()
											.toLowerCase();
									if (!ontologyTerms.contains(ontologyTerm)) ontologyTerms.add(ontologyTerm);
									if (!totalHits.containsKey(ontologyIRI)) totalHits.put(ontologyIRI,
											new OntologyTermContainer(ontologyIRI));
									totalHits.get(ontologyIRI).getAllPaths().put(nodePath, boost);
								}
							}
							positions.add(createQueryRules(StringUtils.join(ontologyTerms.toArray(), ' '), totalHits));
						}
					}
				}
			}
		}
		return positions;
	}

	private Map<Integer, List<BoostTermContainer>> createQueryRules(String description,
			Map<String, OntologyTermContainer> totalHits)
	{
		Map<Integer, List<BoostTermContainer>> position = new HashMap<Integer, List<BoostTermContainer>>();
		List<String> uniqueTokens = stemMembers(Arrays.asList(description.split(" +")));

		for (OntologyTermContainer ontologyTermContainer : totalHits.values())
		{
			Set<String> existingQueryStrings = new HashSet<String>();
			for (Entry<String, Boolean> entry : ontologyTermContainer.getAllPaths().entrySet())
			{
				String documentType = "ontologyTerm-" + ontologyTermContainer.getOntologyIRI();
				String parentNodePath = entry.getKey();
				int parentNodeLevel = parentNodePath.split("\\.").length;
				Boolean boost = entry.getValue();

				Query query = new QueryImpl().eq(NODE_PATH, entry.getKey()).pageSize(5000);
				SearchResult result = searchService.search(new SearchRequest(documentType, query, null));
				Iterator<Hit> iterator = result.iterator();

				Pattern pattern = Pattern.compile("[0-9]+");
				Matcher matcher = null;

				BoostTermContainer boostTermContainer = new BoostTermContainer(parentNodePath,
						new LinkedHashSet<String>(), boost);
				int finalIndexPosition = -1;

				while (iterator.hasNext())
				{
					Hit hit = iterator.next();
					Map<String, Object> columnValueMap = hit.getColumnValueMap();
					String nodePath = columnValueMap.get(NODE_PATH).toString();
					String ontologyTermSynonym = columnValueMap.get(ONTOLOGYTERM_SYNONYM).toString().trim()
							.toLowerCase();

					if (!existingQueryStrings.contains(ontologyTermSynonym))
					{
						existingQueryStrings.add(ontologyTermSynonym);

						if (nodePath.equals(parentNodePath))
						{
							if (finalIndexPosition == -1) finalIndexPosition = locateTermInDescription(uniqueTokens,
									ontologyTermSynonym);
							if (!ontologyTermSynonym.toString().equals("")) boostTermContainer.getTerms().add(
									ontologyTermSynonym);
						}
						else if (nodePath.startsWith(parentNodePath + "."))
						{
							matcher = pattern.matcher(ontologyTermSynonym);

							if (!matcher.find() && !ontologyTermSynonym.equals(""))
							{
								int levelDown = nodePath.split("\\.").length - parentNodeLevel;
								double boostedNumber = Math.pow(0.5, levelDown);
								if (finalIndexPosition == -1) finalIndexPosition = locateTermInDescription(
										uniqueTokens, ontologyTermSynonym);

								StringBuilder boostedSynonym = new StringBuilder();
								for (String eachToken : ontologyTermSynonym.split(" +"))
								{
									if (eachToken.length() != 0) boostedSynonym.append(' ');
									boostedSynonym.append(eachToken).append('^').append(boostedNumber);
								}
								ontologyTermSynonym = boostedSynonym.toString();
							}

							if (!ontologyTermSynonym.toString().equals("")) boostTermContainer.getTerms().add(
									ontologyTermSynonym);
						}
					}
				}
				if (!position.containsKey(finalIndexPosition)) position.put(finalIndexPosition,
						new ArrayList<BoostTermContainer>());
				position.get(finalIndexPosition).add(boostTermContainer);
			}
		}

		if (!position.containsKey(-2)) position.put(-2, new ArrayList<BoostTermContainer>());
		BoostTermContainer descriptionBoostTermContainer = new BoostTermContainer(null, new LinkedHashSet<String>(),
				false);
		descriptionBoostTermContainer.getTerms().add(removeStopWords(description));
		position.get(-2).add(descriptionBoostTermContainer);

		return position;
	}

	private String removeStopWords(String originalTerm)
	{
		Set<String> tokens = new LinkedHashSet<String>(Arrays.asList(originalTerm.trim().toLowerCase().split(" +")));
		tokens.removeAll(NGramMatchingModel.STOPWORDSLIST);
		return StringUtils.join(tokens.toArray(), ' ');
	}

	private Integer locateTermInDescription(List<String> uniqueSets, String ontologyTermSynonym)
	{
		int finalIndex = -1;
		List<String> termsFromDescription = stemMembers(Arrays.asList(ontologyTermSynonym.split(" +")));
		for (String eachTerm : termsFromDescription)
		{
			if (!uniqueSets.contains(eachTerm))
			{
				return -1;
			}
			else
			{
				int currentIndex = uniqueSets.indexOf(eachTerm);
				if (finalIndex == -1) finalIndex = currentIndex;
				else finalIndex = finalIndex < currentIndex ? finalIndex : currentIndex;
			}
		}
		return finalIndex;
	}

	private List<String> stemMembers(List<String> originalList)
	{
		List<String> newList = new ArrayList<String>();
		for (String eachTerm : originalList)
		{
			eachTerm = eachTerm.toLowerCase().trim();
			if (!NGramMatchingModel.STOPWORDSLIST.contains(eachTerm))
			{
				try
				{
					stemmer.setCurrent(eachTerm);
					stemmer.stem();
					eachTerm = stemmer.getCurrent().toLowerCase();
				}
				catch (RuntimeException e)
				{
					logger.error("Could not stem word : " + eachTerm, e);
				}
				newList.add(eachTerm);
			}
		}
		return newList;
	}

	private void createMappingStore(String userName, Integer selectedDataSet, List<Integer> dataSetsToMatch)
	{
		ObservableFeature f = dataService.findOne(ObservableFeature.ENTITY_NAME,
				new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_FEATURE), ObservableFeature.class);

		if (f == null)
		{
			List<ObservableFeature> features = new ArrayList<ObservableFeature>();

			ObservableFeature feature = new ObservableFeature();
			feature.setIdentifier(STORE_MAPPING_FEATURE);
			feature.setDataType(MolgenisFieldTypes.FieldTypeEnum.INT.toString().toLowerCase());
			feature.setName("Features");
			features.add(feature);

			ObservableFeature mappedFeature = new ObservableFeature();
			mappedFeature.setIdentifier(STORE_MAPPING_MAPPED_FEATURE);
			mappedFeature.setDataType(MolgenisFieldTypes.FieldTypeEnum.INT.toString().toLowerCase());
			mappedFeature.setName("Mapped features");
			features.add(mappedFeature);

			ObservableFeature mappedFeatureScore = new ObservableFeature();
			mappedFeatureScore.setIdentifier(STORE_MAPPING_SCORE);
			mappedFeatureScore.setDataType(MolgenisFieldTypes.FieldTypeEnum.DECIMAL.toString().toLowerCase());
			mappedFeatureScore.setName(STORE_MAPPING_SCORE);
			features.add(mappedFeatureScore);

			ObservableFeature observationSetFeature = new ObservableFeature();
			observationSetFeature.setIdentifier(OBSERVATION_SET);
			observationSetFeature.setDataType(MolgenisFieldTypes.FieldTypeEnum.INT.toString().toLowerCase());
			observationSetFeature.setName(OBSERVATION_SET);
			features.add(observationSetFeature);

			ObservableFeature algorithmScriptFeature = new ObservableFeature();
			algorithmScriptFeature.setIdentifier(STORE_MAPPING_ALGORITHM_SCRIPT);
			algorithmScriptFeature.setDataType(MolgenisFieldTypes.FieldTypeEnum.STRING.toString().toLowerCase());
			algorithmScriptFeature.setName(STORE_MAPPING_ALGORITHM_SCRIPT);
			features.add(algorithmScriptFeature);

			ObservableFeature confirmMapping = new ObservableFeature();
			confirmMapping.setIdentifier(STORE_MAPPING_CONFIRM_MAPPING);
			confirmMapping.setDataType(MolgenisFieldTypes.FieldTypeEnum.BOOL.toString().toLowerCase());
			confirmMapping.setName("Mapping confirmed");
			features.add(confirmMapping);

			dataService.add(ObservableFeature.ENTITY_NAME, features);

			Protocol protocol = new Protocol();
			protocol.setIdentifier(PROTOCOL_IDENTIFIER);
			protocol.setName(PROTOCOL_IDENTIFIER);
			protocol.setFeatures(features);
			Integer id = dataService.add(Protocol.ENTITY_NAME, protocol);
			System.out.println(id);
		}

		for (Integer dataSetId : dataSetsToMatch)
		{
			String identifier = createMappingDataSetIdentifier(userName, selectedDataSet, dataSetId);
			DataSet existing = dataService.findOne(DataSet.ENTITY_NAME,
					new QueryImpl().eq(DataSet.IDENTIFIER, identifier), DataSet.class);

			if (existing == null)
			{
				DataSet dataSet = new DataSet();
				dataSet.setIdentifier(identifier);
				dataSet.setName(identifier);

				Protocol protocol = dataService.findOne(Protocol.ENTITY_NAME,
						new QueryImpl().eq(Protocol.IDENTIFIER, PROTOCOL_IDENTIFIER), Protocol.class);
				dataSet.setProtocolUsed(protocol);

				dataSet.setDescription("");
				dataService.add(DataSet.ENTITY_NAME, dataSet);
			}
		}
	}

	@Override
	@RunAsSystem
	public boolean checkExistingMappings(String dataSetIdentifier, DataService dataService)
	{
		DataSet dataSet = dataService.findOne(DataSet.ENTITY_NAME,
				new QueryImpl().eq(DataSet.IDENTIFIER, dataSetIdentifier), DataSet.class);
		if (dataSet == null)
		{
			throw new MolgenisDataException("Unknown DataSet [" + dataSetIdentifier + "]");
		}

		Iterable<ObservationSet> listOfObservationSets = dataService.findAll(ObservationSet.ENTITY_NAME,
				new QueryImpl().eq(ObservationSet.PARTOFDATASET, dataSet), ObservationSet.class);

		return Iterables.size(listOfObservationSets) > 0;
	}

	@Override
	@RunAsSystem
	@Transactional
	public Map<String, String> updateScript(String userName, OntologyMatcherRequest request)
	{
		Map<String, String> updateResult = new HashMap<String, String>();
		// check if the dataset for mappings has been created
		createMappingStore(userName, request.getTargetDataSetId(), request.getSelectedDataSetIds());

		// check if the mapping that needs to be updated has been created
		String mappingDataSetIdentifier = createMappingDataSetIdentifier(userName, request.getTargetDataSetId(),
				request.getSelectedDataSetIds().get(0));

		// update the existing mappings
		if (updateExistingMapping(mappingDataSetIdentifier, request))
		{
			updateResult.put("message", "the script has been updated!");
			return updateResult;
		}

		// add the new mappings
		addNewMappingToDatabase(mappingDataSetIdentifier, request);
		updateResult.put("message", "the script has been added to the database!");
		return updateResult;
	}

	private void addNewMappingToDatabase(String mappingDataSetIdentifier, OntologyMatcherRequest request)
	{
		List<ObservedValue> listOfNewObservedValues = new ArrayList<ObservedValue>();

		DataSet storingMappingDataSet = dataService.findOne(DataSet.ENTITY_NAME,
				new QueryImpl().eq(DataSet.IDENTIFIER, mappingDataSetIdentifier), DataSet.class);
		ObservationSet observationSet = new ObservationSet();
		observationSet.setIdentifier(mappingDataSetIdentifier + "-" + request.getFeatureId());
		observationSet.setPartOfDataSet(storingMappingDataSet);
		dataService.add(ObservationSet.ENTITY_NAME, observationSet);

		IntValue xrefForFeature = new IntValue();
		xrefForFeature.setValue(request.getFeatureId());
		dataService.add(IntValue.ENTITY_NAME, xrefForFeature);

		ObservedValue valueForFeature = new ObservedValue();
		valueForFeature.setObservationSet(observationSet);
		ObservableFeature smf = dataService.findOne(ObservableFeature.ENTITY_NAME,
				new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_FEATURE), ObservableFeature.class);
		valueForFeature.setFeature(smf);
		valueForFeature.setValue(xrefForFeature);
		listOfNewObservedValues.add(valueForFeature);

		StringValue algorithmScriptValue = new StringValue();
		algorithmScriptValue.setValue(request.getAlgorithmScript());
		dataService.add(StringValue.ENTITY_NAME, algorithmScriptValue);

		ObservedValue algorithmScriptObservedValue = new ObservedValue();
		algorithmScriptObservedValue.setObservationSet(observationSet);
		ObservableFeature algorithmScriptFeature = dataService.findOne(ObservableFeature.ENTITY_NAME,
				new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_ALGORITHM_SCRIPT),
				ObservableFeature.class);

		algorithmScriptObservedValue.setFeature(algorithmScriptFeature);
		algorithmScriptObservedValue.setValue(algorithmScriptValue);
		listOfNewObservedValues.add(algorithmScriptObservedValue);

		IntValue observationSetIntValue = new IntValue();
		observationSetIntValue.setValue(observationSet.getId());
		dataService.add(IntValue.ENTITY_NAME, observationSetIntValue);

		ObservedValue valueForObservationSet = new ObservedValue();
		ObservableFeature observationSetFeature = dataService.findOne(ObservableFeature.ENTITY_NAME,
				new QueryImpl().eq(ObservableFeature.IDENTIFIER, OBSERVATION_SET), ObservableFeature.class);
		valueForObservationSet.setFeature(observationSetFeature);
		valueForObservationSet.setObservationSet(observationSet);
		valueForObservationSet.setValue(observationSetIntValue);
		listOfNewObservedValues.add(valueForObservationSet);

		dataService.add(ObservedValue.ENTITY_NAME, listOfNewObservedValues);
		searchService.updateRepositoryIndex(new StoreMappingRepository(storingMappingDataSet, listOfNewObservedValues,
				dataService));
	}

	private boolean updateExistingMapping(String mappingDataSetIdentifier, OntologyMatcherRequest request)
	{
		QueryImpl query = new QueryImpl();
		query.pageSize(100000);
		query.addRule(new QueryRule(STORE_MAPPING_FEATURE, Operator.EQUALS, request.getFeatureId()));
		SearchResult result = searchService.search(new SearchRequest(mappingDataSetIdentifier, query, null));

		if (result.getTotalHitCount() > 0)
		{
			Hit hit = result.getSearchHits().get(0);
			Map<String, Object> columnValueMap = hit.getColumnValueMap();

			// Check if the new script is same as old script
			if (columnValueMap.get(STORE_MAPPING_ALGORITHM_SCRIPT) != null
					&& columnValueMap.get(STORE_MAPPING_ALGORITHM_SCRIPT).toString().trim()
							.equalsIgnoreCase(request.getAlgorithmScript().trim())) return true;

			// Update database
			ObservationSet observationSet = dataService.findOne(ObservationSet.ENTITY_NAME,
					Integer.parseInt(columnValueMap.get(OBSERVATION_SET).toString()), ObservationSet.class);
			if (observationSet == null) return false;

			ObservableFeature storeMappingAlgorithmScriptFeature = dataService.findOne(ObservableFeature.ENTITY_NAME,
					new QueryImpl().eq(ObservableFeature.IDENTIFIER, STORE_MAPPING_ALGORITHM_SCRIPT),
					ObservableFeature.class);

			ObservedValue algorithmScriptObservedValue = dataService.findOne(
					ObservedValue.ENTITY_NAME,
					new QueryImpl().eq(ObservedValue.OBSERVATIONSET, observationSet).and()
							.eq(ObservedValue.FEATURE, storeMappingAlgorithmScriptFeature), ObservedValue.class);

			if (algorithmScriptObservedValue.getValue() instanceof StringValue)
			{
				StringValue algorithmScriptValue = (StringValue) algorithmScriptObservedValue.getValue();
				algorithmScriptValue.setValue(request.getAlgorithmScript());
				dataService.update(StringValue.ENTITY_NAME, algorithmScriptValue);

				// Update index
				StringBuilder updateScriptBuilder = new StringBuilder();
				updateScriptBuilder.append(STORE_MAPPING_ALGORITHM_SCRIPT).append('=').append("\"")
						.append(request.getAlgorithmScript()).append("\"");
				searchService.updateDocumentById(mappingDataSetIdentifier, hit.getId(), updateScriptBuilder.toString());
			}
		}
		return result.getTotalHitCount() > 0;
	}

	private String createMappingDataSetIdentifier(String userName, Integer targetDataSetId, Integer sourceDataSetId)
	{
		StringBuilder dataSetIdentifier = new StringBuilder();
		dataSetIdentifier.append(userName).append('-').append(targetDataSetId).append('-').append(sourceDataSetId);
		return dataSetIdentifier.toString();
	}

	public class BoostTermContainer
	{
		private final String parentNodePath;
		private boolean boost;
		private final LinkedHashSet<String> terms;

		public BoostTermContainer(String parentNodePath, LinkedHashSet<String> terms, boolean boost)
		{
			this.parentNodePath = parentNodePath;
			this.terms = terms;
			this.boost = boost;
		}

		public LinkedHashSet<String> getTerms()
		{
			return terms;
		}

		public void setBoost(boolean boost)
		{
			if (!this.boost) this.boost = boost;
		}

		public boolean isBoost()
		{
			return boost;
		}

		public String getParentNodePath()
		{
			return parentNodePath;
		}
	}

	public class OntologyTermContainer
	{
		private final String ontologyIRI;
		private final HashMap<String, String> alternativeDefinitions;
		private final Map<String, Boolean> allPaths;
		private final Set<String> selectedOntologyTerms;

		public OntologyTermContainer(String ontologyIRI)
		{
			this.ontologyIRI = ontologyIRI;
			this.alternativeDefinitions = new HashMap<String, String>();
			this.allPaths = new HashMap<String, Boolean>();
			this.selectedOntologyTerms = new HashSet<String>();

		}

		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((ontologyIRI == null) ? 0 : ontologyIRI.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj)
		{
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			OntologyTermContainer other = (OntologyTermContainer) obj;
			if (!getOuterType().equals(other.getOuterType())) return false;
			if (ontologyIRI == null)
			{
				if (other.ontologyIRI != null) return false;
			}
			else if (!ontologyIRI.equals(other.ontologyIRI)) return false;
			return true;
		}

		private AsyncOntologyMatcher getOuterType()
		{
			return AsyncOntologyMatcher.this;
		}

		public String getOntologyIRI()
		{
			return ontologyIRI;
		}

		public Map<String, Boolean> getAllPaths()
		{
			return allPaths;
		}

		public HashMap<String, String> getAlternativeDefinitions()
		{
			return alternativeDefinitions;
		}

		public Set<String> getSelectedOntologyTerms()
		{
			return selectedOntologyTerms;
		}
	}
}