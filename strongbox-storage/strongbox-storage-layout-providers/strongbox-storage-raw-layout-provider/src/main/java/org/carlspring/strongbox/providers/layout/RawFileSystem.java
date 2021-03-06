package org.carlspring.strongbox.providers.layout;

import org.carlspring.strongbox.providers.io.LayoutFileSystem;
import org.carlspring.strongbox.providers.io.StorageFileSystemProvider;
import org.carlspring.strongbox.storage.repository.Repository;

import javax.inject.Inject;
import java.nio.file.FileSystem;
import java.util.Set;

/**
 * @author sbespalov
 *
 */
public class RawFileSystem extends LayoutFileSystem
{

    @Inject
    private RawLayoutProvider layoutProvider;

    public RawFileSystem(Repository repository,
                         FileSystem storageFileSystem,
                         StorageFileSystemProvider provider)
    {
        super(repository, storageFileSystem, provider);
    }

    @Override
    public Set<String> getDigestAlgorithmSet()
    {
        return layoutProvider.getDigestAlgorithmSet();
    }

}
