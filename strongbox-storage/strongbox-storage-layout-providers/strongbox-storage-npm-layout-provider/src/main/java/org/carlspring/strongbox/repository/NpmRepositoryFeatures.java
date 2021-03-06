package org.carlspring.strongbox.repository;

import org.carlspring.strongbox.config.NpmLayoutProviderConfig.NpmObjectMapper;
import org.carlspring.strongbox.configuration.Configuration;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.data.criteria.Expression.ExpOperator;
import org.carlspring.strongbox.data.criteria.OQueryTemplate;
import org.carlspring.strongbox.data.criteria.Predicate;
import org.carlspring.strongbox.data.criteria.Selector;
import org.carlspring.strongbox.domain.RemoteArtifactEntry;
import org.carlspring.strongbox.npm.NpmSearchRequest;
import org.carlspring.strongbox.npm.NpmViewRequest;
import org.carlspring.strongbox.npm.metadata.Change;
import org.carlspring.strongbox.npm.metadata.PackageFeed;
import org.carlspring.strongbox.npm.metadata.SearchResults;
import org.carlspring.strongbox.providers.repository.event.RemoteRepositorySearchEvent;
import org.carlspring.strongbox.service.ProxyRepositoryConnectionPoolConfigurationService;
import org.carlspring.strongbox.services.ConfigurationManagementService;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.repository.MutableRepository;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.repository.remote.RemoteRepository;
import org.carlspring.strongbox.storage.validation.artifact.version.GenericReleaseVersionValidator;
import org.carlspring.strongbox.storage.validation.artifact.version.GenericSnapshotVersionValidator;
import org.carlspring.strongbox.storage.validation.deployment.RedeploymentValidator;
import org.carlspring.strongbox.xml.configuration.repository.remote.MutableNpmRemoteRepositoryConfiguration;
import org.carlspring.strongbox.xml.configuration.repository.remote.NpmRemoteRepositoryConfiguration;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class NpmRepositoryFeatures implements RepositoryFeatures
{

    private static final int CHANGES_BATCH_SIZE = 500;

    private static final Logger logger = LoggerFactory.getLogger(NpmRepositoryFeatures.class);

    @Inject
    private ConfigurationManagementService configurationManagementService;

    @Inject
    private RedeploymentValidator redeploymentValidator;

    @Inject
    private GenericReleaseVersionValidator genericReleaseVersionValidator;

    @Inject
    private GenericSnapshotVersionValidator genericSnapshotVersionValidator;

    @Inject
    private ProxyRepositoryConnectionPoolConfigurationService proxyRepositoryConnectionPoolConfigurationService;

    @Inject
    private ConfigurationManager configurationManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Inject
    private Executor eventTaskExecutor;

    @Inject
    @NpmObjectMapper
    private ObjectMapper npmJacksonMapper;

    @Inject
    private NpmPackageFeedParser npmPackageFeedParser;

    private Set<String> defaultArtifactCoordinateValidators;

    @PostConstruct
    public void init()
    {
        defaultArtifactCoordinateValidators = new LinkedHashSet<>(Arrays.asList(redeploymentValidator.getAlias(),
                                                                                genericReleaseVersionValidator.getAlias(),
                                                                                genericSnapshotVersionValidator.getAlias()));
    }

    @Override
    public Set<String> getDefaultArtifactCoordinateValidators()
    {
        return defaultArtifactCoordinateValidators;
    }

    private void fetchRemoteSearchResult(String storageId,
                                         String repositoryId,
                                         String text,
                                         Integer size)
    {

        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        RemoteRepository remoteRepository = repository.getRemoteRepository();
        if (remoteRepository == null)
        {
            return;
        }
        String remoteRepositoryUrl = remoteRepository.getUrl();

        SearchResults searchResults;
        Client restClient = proxyRepositoryConnectionPoolConfigurationService.getRestClient();
        try
        {
            logger.debug(String.format("Search NPM packages for [%s].", remoteRepositoryUrl));

            WebTarget service = restClient.target(remoteRepository.getUrl());
            service = service.path("-/v1/search").queryParam("text", text).queryParam("size", size);

            InputStream inputStream = service.request().buildGet().invoke(InputStream.class);
            searchResults = npmJacksonMapper.readValue(inputStream, SearchResults.class);

            logger.debug(String.format("Searched NPM packages for [%s].", remoteRepository.getUrl()));

        }
        catch (Exception e)
        {
            logger.error(String.format("Failed to searhc NPM packages [%s]", remoteRepositoryUrl), e);
            
            return;
        } 
        finally
        {
            restClient.close();
        }

        try
        {
            npmPackageFeedParser.parseSearchResult(repository, searchResults);
        }
        catch (Exception e)
        {
            logger.error(String.format("Failed to parse NPM packages search result for [%s]", remoteRepositoryUrl), e);
        }
    }

    public void fetchRemoteChangesFeed(String storageId,
                                       String repositoryId)
        throws IOException
    {

        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        RemoteRepository remoteRepository = repository.getRemoteRepository();
        if (remoteRepository == null)
        {
            return;
        }

        MutableRepository mutableRepository = configurationManagementService.getMutableConfigurationClone()
                                                                            .getStorage(storageId)
                                                                            .getRepository(repositoryId);
        MutableNpmRemoteRepositoryConfiguration mutableConfiguration = (MutableNpmRemoteRepositoryConfiguration) mutableRepository.getRemoteRepository()
                                                                                                                                  .getCustomConfiguration();

        NpmRemoteRepositoryConfiguration configuration = (NpmRemoteRepositoryConfiguration) remoteRepository.getCustomConfiguration();
        if (configuration == null)
        {
            logger.warn(String.format("Remote npm configuration not found for [%s]/[%s]", storageId, repositoryId));
            return;
        }
        Long lastCnahgeId = configuration.getLastChangeId();
        String replicateUrl = configuration.getReplicateUrl();

        Long nextChangeId = lastCnahgeId;
        do
        {
            lastCnahgeId = nextChangeId;
            mutableConfiguration.setLastChangeId(nextChangeId);
            configurationManagementService.saveRepository(storageId, mutableRepository);

            nextChangeId = Long.valueOf(fetchRemoteChangesFeed(repository, replicateUrl, lastCnahgeId + 1));
        } while (nextChangeId > lastCnahgeId);
    }

    private Integer fetchRemoteChangesFeed(Repository repository,
                                           String replicateUrl,
                                           Long since)
        throws IOException
    {
        int result = 0;
        Client restClient = proxyRepositoryConnectionPoolConfigurationService.getRestClient();
        try
        {
            logger.debug(String.format("Fetching remote cnages for [%s] since [%s].", replicateUrl, since));

            WebTarget service = restClient.target(replicateUrl);
            service = service.path("_changes");
            service = service.queryParam("since", since);
            service = service.queryParam("include_docs", true);
            service = service.queryParam("limit", CHANGES_BATCH_SIZE);

            Invocation request = service.request().buildGet();

            result = fetchRemoteChangesFeed(repository, request);
        } 
        finally
        {
            restClient.close();
        }

        return result;
    }

    private int fetchRemoteChangesFeed(Repository repository,
                                       Invocation request)
        throws IOException
    {
        int result = 0;

        RemoteRepository remoteRepository = repository.getRemoteRepository();
        NpmRemoteRepositoryConfiguration repositoryConfiguration = (NpmRemoteRepositoryConfiguration) remoteRepository.getCustomConfiguration();

        JsonFactory jfactory = new JsonFactory();

        try (InputStream is = request.invoke(InputStream.class))
        {

            JsonParser jp = jfactory.createParser(is);
            jp.setCodec(npmJacksonMapper);

            Assert.isTrue(jp.nextToken() == JsonToken.START_OBJECT, "npm changes feed should be JSON object.");
            Assert.isTrue(jp.nextFieldName().equals("results"), "npm changes feed should contains `results` field.");
            Assert.isTrue(jp.nextToken() == JsonToken.START_ARRAY, "npm changes feed `results` should be array.");

            StringBuffer sb = new StringBuffer();
            while (jp.nextToken() != null)
            {
                JsonToken nextToken = jp.currentToken();
                if (nextToken == JsonToken.END_ARRAY)
                {
                    break;
                }

                JsonNode node = jp.readValueAsTree();
                sb.append(node.toString());

                String changeValue = sb.toString();

                Change change;
                try
                {
                    change = npmJacksonMapper.readValue(changeValue, Change.class);
                }
                catch (Exception e)
                {
                    logger.error(String.format("Failed to parse NPM cnahges feed [%s] since [%s]: %n %s",
                                               repositoryConfiguration.getReplicateUrl(),
                                               repositoryConfiguration.getLastChangeId(),
                                               changeValue),
                                 e);

                    return result;
                }

                PackageFeed packageFeed = change.getDoc();
                try
                {
                    npmPackageFeedParser.parseFeed(repository, packageFeed);
                }
                catch (Exception e)
                {
                    logger.error(String.format("Failed to parse NPM feed [%s/%s]",
                                               repository.getRemoteRepository().getUrl(),
                                               packageFeed.getName()),
                                 e);

                }

                result = change.getSeq();
                sb = new StringBuffer();
            }

        }

        logger.debug(String.format("Fetched remote cnages for  [%s] since [%s].",
                                   repositoryConfiguration.getReplicateUrl(),
                                   repositoryConfiguration.getLastChangeId()));

        return result;
    }

    private void fetchRemotePackageFeed(String storageId,
                                        String repositoryId,
                                        String packageId)
    {

        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        RemoteRepository remoteRepository = repository.getRemoteRepository();
        if (remoteRepository == null)
        {
            return;
        }
        String remoteRepositoryUrl = remoteRepository.getUrl();

        PackageFeed packageFeed;
        Client restClient = proxyRepositoryConnectionPoolConfigurationService.getRestClient();
        try
        {
            logger.debug(String.format("Downloading NPM changes feed for [%s].", remoteRepositoryUrl));

            WebTarget service = restClient.target(remoteRepository.getUrl());
            service = service.path(packageId);

            InputStream inputStream = service.request().buildGet().invoke(InputStream.class);
            packageFeed = npmJacksonMapper.readValue(inputStream, PackageFeed.class);

            logger.debug(String.format("Downloaded NPM changes feed for [%s].", remoteRepository.getUrl()));

        }
        catch (Exception e)
        {
            logger.error(String.format("Failed to fetch NPM changes feed [%s]", remoteRepositoryUrl), e);
            return;
        } 
        finally
        {
            restClient.close();
        }

        try
        {
            npmPackageFeedParser.parseFeed(repository, packageFeed);
        }
        catch (Exception e)
        {
            logger.error(String.format("Failed to parse NPM feed [%s/%s]",
                                       repository.getRemoteRepository().getUrl(),
                                       packageFeed.getName()),
                         e);
        }
    }

    @Component
    @Scope(scopeName = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public class SearchPackagesEventListener
    {

        private NpmSearchRequest npmSearchRequest;

        public NpmSearchRequest getNpmSearchRequest()
        {
            return npmSearchRequest;
        }

        public void setNpmSearchRequest(NpmSearchRequest npmSearchRequest)
        {
            this.npmSearchRequest = npmSearchRequest;
        }

        @EventListener
        public void handle(RemoteRepositorySearchEvent event)
        {
            if (npmSearchRequest == null)
            {
                return;
            }

            String storageId = event.getStorageId();
            String repositoryId = event.getRepositoryId();

            Storage storage = getConfiguration().getStorage(storageId);
            Repository repository = storage.getRepository(repositoryId);
            RemoteRepository remoteRepository = repository.getRemoteRepository();
            if (remoteRepository == null)
            {
                return;
            }

            Predicate predicate = event.getPredicate();
            Long packageCount = countPackages(storageId, repositoryId, predicate);

            logger.debug(String.format("NPM remote repository [%s] cached package count is [%s]", repository.getId(),
                                       packageCount));

            Runnable job = () -> fetchRemoteSearchResult(storageId, repositoryId, npmSearchRequest.getText(),
                                                         npmSearchRequest.getSize());
            if (packageCount.longValue() == 0)
            {
                // Syncronously fetch remote package feed if ve have no cached
                // packages
                job.run();
            }
            else
            {
                eventTaskExecutor.execute(job);
            }

        }
    }

    @Component
    @Scope(scopeName = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public class ViewPackageEventListener
    {

        private NpmViewRequest npmSearchRequest;

        public NpmViewRequest getNpmSearchRequest()
        {
            return npmSearchRequest;
        }

        public void setNpmSearchRequest(NpmViewRequest npmSearchRequest)
        {
            this.npmSearchRequest = npmSearchRequest;
        }

        @EventListener
        public void handle(RemoteRepositorySearchEvent event)
        {
            if (npmSearchRequest == null)
            {
                return;
            }

            String storageId = event.getStorageId();
            String repositoryId = event.getRepositoryId();

            Storage storage = getConfiguration().getStorage(storageId);
            Repository repository = storage.getRepository(repositoryId);
            RemoteRepository remoteRepository = repository.getRemoteRepository();
            if (remoteRepository == null)
            {
                return;
            }

            Predicate predicate = event.getPredicate();
            Long packageCount = countPackages(storageId, repositoryId, predicate);

            logger.debug(String.format("NPM remote repository [%s] cached package count is [%s]", repository.getId(),
                                       packageCount));

            Runnable job = () -> fetchRemotePackageFeed(storage.getId(), repository.getId(),
                                                        npmSearchRequest.getPackageId());
            if (packageCount.longValue() == 0)
            {
                // Syncronously fetch remote package feed if ve have no cached
                // packages
                job.run();
            }
            else
            {
                eventTaskExecutor.execute(job);
            }
        }

    }

    private Long countPackages(String storageId,
                               String repositoryId,
                               Predicate predicate)
    {
        Selector<RemoteArtifactEntry> selector = new Selector<>(RemoteArtifactEntry.class);
        selector.select("count(*)");
        selector.where(Predicate.of(ExpOperator.EQ.of("storageId", storageId)))
                .and(Predicate.of(ExpOperator.EQ.of("repositoryId", repositoryId)));
        if (!predicate.isEmpty())
        {
            selector.getPredicate().and(predicate);
        }
        OQueryTemplate<Long, RemoteArtifactEntry> queryTemplate = new OQueryTemplate<>(entityManager);
        Long packageCount = queryTemplate.select(selector);
        return packageCount;
    }

    protected Configuration getConfiguration()
    {
        return configurationManager.getConfiguration();
    }

}
