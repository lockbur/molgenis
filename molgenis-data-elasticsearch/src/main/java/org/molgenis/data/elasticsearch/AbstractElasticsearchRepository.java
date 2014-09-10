package org.molgenis.data.elasticsearch;

import static org.molgenis.elasticsearch.util.ElasticsearchEntityUtils.toElasticsearchId;
import static org.molgenis.elasticsearch.util.ElasticsearchEntityUtils.toElasticsearchIds;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import org.molgenis.data.AggregateResult;
import org.molgenis.data.Aggregateable;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.CrudRepository;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.Query;
import org.molgenis.data.support.ConvertingIterable;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.elasticsearch.ElasticSearchService;
import org.molgenis.elasticsearch.ElasticSearchService.IndexingMode;
import org.molgenis.search.SearchRequest;
import org.molgenis.search.SearchResult;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Iterables;

public abstract class AbstractElasticsearchRepository implements CrudRepository, Aggregateable
{
	public static final String BASE_URL = "elasticsearch://";

	protected final ElasticSearchService elasticSearchService;

	public AbstractElasticsearchRepository(ElasticSearchService elasticSearchService)
	{
		if (elasticSearchService == null) throw new IllegalArgumentException("elasticSearchService is null");
		this.elasticSearchService = elasticSearchService;
	}

	@Override
	public abstract EntityMetaData getEntityMetaData();

	@Override
	public <E extends Entity> Iterable<E> iterator(Class<E> clazz)
	{
		@SuppressWarnings("resource")
		final AbstractElasticsearchRepository self = this;
		return new ConvertingIterable<E>(clazz, new Iterable<Entity>()
		{

			@Override
			public Iterator<Entity> iterator()
			{
				return self.iterator();
			}
		});
	}

	@Override
	public String getUrl()
	{
		return BASE_URL + getName() + '/';
	}

	@Override
	public long count()
	{
		return elasticSearchService.count(getEntityMetaData());
	}

	@Override
	public Query query()
	{
		return new QueryImpl(this);
	}

	@Override
	public long count(Query q)
	{
		return elasticSearchService.count(q, getEntityMetaData());
	}

	@Override
	public Iterable<Entity> findAll(Query q)
	{
		return elasticSearchService.search(q, getEntityMetaData());
	}

	@Override
	public <E extends Entity> Iterable<E> findAll(Query q, Class<E> clazz)
	{
		return new ConvertingIterable<E>(clazz, findAll(q));
	}

	@Override
	public Entity findOne(Query q)
	{
		Iterable<Entity> entities = elasticSearchService.search(q, getEntityMetaData());
		return !Iterables.isEmpty(entities) ? entities.iterator().next() : null;
	}

	@Override
	public Entity findOne(Object id)
	{
		return elasticSearchService.get(id, getEntityMetaData());
	}

	@Override
	public Iterable<Entity> findAll(Iterable<Object> ids)
	{
		return elasticSearchService.get(ids, getEntityMetaData());
	}

	@Override
	public <E extends Entity> Iterable<E> findAll(Iterable<Object> ids, Class<E> clazz)
	{
		return new ConvertingIterable<E>(clazz, findAll(ids));
	}

	@Override
	public <E extends Entity> E findOne(Object id, Class<E> clazz)
	{
		Entity entity = findOne(id);
		return new ConvertingIterable<E>(clazz, Arrays.asList(entity)).iterator().next();
	}

	@Override
	public <E extends Entity> E findOne(Query q, Class<E> clazz)
	{
		Entity entity = findOne(q);
		return new ConvertingIterable<E>(clazz, Arrays.asList(entity)).iterator().next();
	}

	@Override
	public Iterator<Entity> iterator()
	{
		return findAll(new QueryImpl()).iterator();
	}

	@Override
	public void close() throws IOException
	{
		// noop
	}

	@Override
	public String getName()
	{
		return getEntityMetaData().getName();
	}

	@Override
	public AggregateResult aggregate(AttributeMetaData xAttr, AttributeMetaData yAttr, Query q)
	{
		SearchRequest searchRequest = new SearchRequest(getName(), q, Collections.<String> emptyList(), xAttr, yAttr);
		SearchResult searchResults = elasticSearchService.search(searchRequest);
		return searchResults.getAggregate();
	}

	@Override
	@Transactional
	public void add(Entity entity)
	{
		elasticSearchService.index(entity, getEntityMetaData(), IndexingMode.ADD);
	}

	@Override
	@Transactional
	public Integer add(Iterable<? extends Entity> entities)
	{
		elasticSearchService.index(entities, getEntityMetaData(), IndexingMode.ADD);
		return Iterables.size(entities); // TODO solve possible performance bottleneck
	}

	@Override
	public void flush()
	{
		elasticSearchService.flush();
	}

	@Override
	public void clearCache()
	{
		// noop
	}

	@Override
	@Transactional
	public void update(Entity entity)
	{
		elasticSearchService.index(entity, getEntityMetaData(), IndexingMode.UPDATE);
	}

	@Override
	@Transactional
	public void update(Iterable<? extends Entity> entities)
	{
		elasticSearchService.index(entities, getEntityMetaData(), IndexingMode.UPDATE);
	}

	@Override
	@Transactional
	public void delete(Entity entity)
	{
		elasticSearchService.delete(entity, getEntityMetaData());
	}

	@Override
	@Transactional
	public void delete(Iterable<? extends Entity> entities)
	{
		elasticSearchService.delete(entities, getEntityMetaData());
	}

	@Override
	@Transactional
	public void deleteById(Object id)
	{
		elasticSearchService.deleteById(toElasticsearchId(id), getEntityMetaData());
	}

	@Override
	@Transactional
	public void deleteById(Iterable<Object> ids)
	{
		elasticSearchService.deleteById(toElasticsearchIds(ids), getEntityMetaData());
	}

	@Override
	@Transactional
	public void deleteAll()
	{
		elasticSearchService.delete(getEntityMetaData());
	}
}
