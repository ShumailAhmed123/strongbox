package org.carlspring.strongbox.services.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.carlspring.strongbox.artifact.coordinates.AbstractArtifactCoordinates;
import org.carlspring.strongbox.artifact.coordinates.ArtifactCoordinates;
import org.carlspring.strongbox.data.service.CommonCrudService;
import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.services.ArtifactEntryService;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;

import edu.emory.mathcs.backport.java.util.Arrays;

/**
 * DAO implementation for {@link ArtifactEntry} entities.
 *
 * @author Alex Oreshkevich
 */
@Service
@Transactional
class ArtifactEntryServiceImpl extends CommonCrudService<ArtifactEntry>
        implements ArtifactEntryService
{

    private static final Logger logger = LoggerFactory.getLogger(ArtifactEntryService.class);

    
    @Override
    public Class<ArtifactEntry> getEntityClass()
    {
        return ArtifactEntry.class;
    }
    
    @Override
    public <S extends ArtifactEntry> S save(S entity)
    {
        
        
        return super.save(entity);
    }

    @Override
    public List<ArtifactEntry> findAritifactList(String storageId,
                                                 String repositoryId,
                                                 Map<String, String> coordinates)
    {
        return findAritifactList(storageId, repositoryId, coordinates, 0, -1, null, false);
    }

    @Override
    @Transactional
    public List<ArtifactEntry> findAritifactList(String storageId,
                                                 String repositoryId,
                                                 Map<String, String> coordinates,
                                                 int skip,
                                                 int limit,
                                                 String orderBy,
                                                 boolean strict)
    {
        if (orderBy == null) {
            orderBy = "uuid";
        }
        coordinates = prepareParameterMap(coordinates, true);
        
        String sQuery = buildCoordinatesQuery(toList(storageId, repositoryId), coordinates.keySet(), skip,
                                              limit, orderBy, strict);
        OSQLSynchQuery<ArtifactEntry> oQuery = new OSQLSynchQuery<>(sQuery);
        
        Map<String, Object> parameterMap = new HashMap<>(coordinates);
        if (storageId != null && !storageId.trim().isEmpty() && repositoryId != null && !repositoryId.trim().isEmpty())
        {
            parameterMap.put("storageId0", storageId);
            parameterMap.put("repositoryId0", repositoryId);
        }
        
        List<ArtifactEntry> entries = getDelegate().command(oQuery).execute(parameterMap);

        return entries;
    }

    @Override
    public List<ArtifactEntry> findAritifactList(String storageId,
                                                 String repositoryId,
                                                 ArtifactCoordinates coordinates)
    {
        if (coordinates == null)
        {
            return findAritifactList(storageId, repositoryId, new HashMap<>());
        }
        return findAritifactList(storageId, repositoryId, coordinates.getCoordinates());
    }
    
    @Override
    public Long countCoordinates(Collection<Pair<String, String>> storageRepositoryPairList,
                                 Map<String, String> coordinates,
                                 boolean strict)
    {
        coordinates = prepareParameterMap(coordinates, strict);
        String sQuery = buildCoordinatesQuery(storageRepositoryPairList, coordinates.keySet(), 0, 0, null, strict);
        sQuery = sQuery.replace("*", "count(distinct(artifactCoordinates))");
        OSQLSynchQuery<ArtifactEntry> oQuery = new OSQLSynchQuery<>(sQuery);
        
        Map<String, Object> parameterMap = new HashMap<>(coordinates);
        
        Pair<String, String>[] p = storageRepositoryPairList.toArray(new Pair[storageRepositoryPairList.size()]);
        IntStream.range(0, storageRepositoryPairList.size()).forEach(idx -> {
            parameterMap.put(String.format("storageId%s", idx), p[idx].getValue0());
            parameterMap.put(String.format("repositoryId%s", idx), p[idx].getValue1());
        });
        
        
        List<ODocument> result = getDelegate().command(oQuery).execute(parameterMap);
        return (Long) result.iterator().next().field("count");
    }

    @Override
    public Long countAritifacts(Collection<Pair<String, String>> storageRepositoryPairList,
                                   Map<String, String> coordinates,
                                   boolean strict)
    {
        coordinates = prepareParameterMap(coordinates, strict);
        String sQuery = buildCoordinatesQuery(storageRepositoryPairList, coordinates.keySet(), 0, 0, null, strict);
        sQuery = sQuery.replace("*", "count(*)");
        OSQLSynchQuery<ArtifactEntry> oQuery = new OSQLSynchQuery<>(sQuery);
        
        Map<String, Object> parameterMap = new HashMap<>(coordinates);
        
        Pair<String, String>[] p = storageRepositoryPairList.toArray(new Pair[storageRepositoryPairList.size()]);
        IntStream.range(0, storageRepositoryPairList.size()).forEach(idx -> {
            parameterMap.put(String.format("storageId%s", idx), p[idx].getValue0());
            parameterMap.put(String.format("repositoryId%s", idx), p[idx].getValue1());
        });
        
        
        List<ODocument> result = getDelegate().command(oQuery).execute(parameterMap);
        return (Long) result.iterator().next().field("count");
    }

    @Override
    public Long countAritifacts(String storageId,
                                String repositoryId,
                                Map<String, String> coordinates,
                                boolean strict)
    {
        return countAritifacts(toList(storageId, repositoryId), coordinates,
                                  strict);
    }

    public List<Pair<String, String>> toList(String storageId,
                                             String repositoryId)
    {
        if (storageId != null && !storageId.trim().isEmpty() && repositoryId != null && !repositoryId.trim().isEmpty())
        {
            return Arrays.asList(new Pair[] { Pair.with(storageId, repositoryId) });            
        }
        throw new IllegalArgumentException("Both parameters 'storageId' and 'repositoryId' should be provided.");
    }
    
    protected String buildCoordinatesQuery(Collection<Pair<String, String>> storageRepositoryPairList,
                                           Set<String> parameterNameSet,
                                           int skip,
                                           int limit,
                                           String orderBy,
                                           boolean strict)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(getEntityClass().getSimpleName());

        //COODRINATES
        StringBuffer c1 = new StringBuffer();
        parameterNameSet.stream()
                        .forEach(e -> c1.append(c1.length() > 0 ? " AND " : "")
                                        .append("artifactCoordinates.coordinates.")
                                        .append(e)
                                        .append(".toLowerCase()")
                                        .append(strict ? " = " : " like ")
                                        .append(String.format(":%s", e)));
        sb.append(" WHERE ").append(c1.length() > 0 ? c1.append(" AND ").toString() : " true = true AND ");

        //REPOSITORIES
        StringBuffer c2 = new StringBuffer();
        IntStream.range(0, storageRepositoryPairList.size())
                 .forEach(idx -> c2.append(idx > 0 ? " OR " : "")
                                   .append(String.format("(storageId = :storageId%s AND repositoryId = :repositoryId%s)",
                                                         idx, idx)));
        sb.append(c2.length() > 0 ? c2.toString() : "true");

        //ORDER
        if ("uuid".equals(orderBy))
        {
            sb.append(" ORDER BY artifactCoordinates.uuid");
        }
        else if (orderBy != null && !orderBy.trim().isEmpty())
        {
            sb.append(String.format(" ORDER BY artifactCoordinates.coordinates.%s", orderBy));
        }
        
        //PAGE
        if (skip > 0)
        {
            sb.append(String.format(" SKIP %s", skip));
        }
        if (limit > 0)
        {
            sb.append(String.format(" LIMIT %s", limit));
        }
        
        // now query should looks like
        // SELECT * FROM Foo WHERE blah = :blah AND moreBlah = :moreBlah

        logger.debug("Executing SQL query> " + sb.toString());

        return sb.toString();
    }

    private Map<String, String> prepareParameterMap(Map<String, String> coordinates,
                                                    boolean strict)
    {
        return coordinates.entrySet()
                          .stream()
                          .filter(e -> e.getValue() != null)
                          .collect(Collectors.toMap(Map.Entry::getKey,
                                                    e -> calculatePatameterValue(e, strict)));
    }

    private String calculatePatameterValue(Entry<String, String> e,
                                           boolean strict)
    {
        String result = e.getValue() == null ? null : e.getValue().toLowerCase();
        if (!strict)
        {
            result += "%" + result + "%";
        }
        return result;
    }

    @Override
    public boolean aritifactExists(String storageId,
                                   String repositoryId,
                                   String path)
    {
        return findArtifactEntryId(storageId, repositoryId, path) != null;
    }

    @Override
    public Optional<ArtifactEntry> findOneAritifact(String storageId,
                                                    String repositoryId,
                                                    String path)
    {
        ORID artifactEntryIdId = findArtifactEntryId(storageId, repositoryId, path);
        return artifactEntryIdId == null ? Optional.empty()
                : Optional.of(entityManager.find(ArtifactEntry.class, artifactEntryIdId));
    }

    private ORID findArtifactEntryId(String storageId, String repositoryId, String path)
    {
        String sQuery = String.format("SELECT FROM INDEX:idx_artifact WHERE key = [:storageId, :repositoryId, :path]");

        OSQLSynchQuery<ODocument> oQuery = new OSQLSynchQuery<>(sQuery);
        oQuery.setLimit(1);

        HashMap<String, Object> params = new HashMap<>();
        params.put("storageId", storageId);
        params.put("repositoryId", repositoryId);
        params.put("path", path);

        List<ODocument> resultList = getDelegate().command(oQuery).execute(params);
        ODocument result = resultList.isEmpty() ? null : resultList.iterator().next();
        return result == null ? null : ((ODocument)result.field("rid")).getIdentity();
    }

}
