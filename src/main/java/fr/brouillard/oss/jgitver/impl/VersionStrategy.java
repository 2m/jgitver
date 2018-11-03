/**
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.brouillard.oss.jgitver.impl;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import fr.brouillard.oss.jgitver.Lambdas;
import fr.brouillard.oss.jgitver.Version;
import fr.brouillard.oss.jgitver.VersionCalculationException;
import fr.brouillard.oss.jgitver.metadata.MetadataRegistrar;
import fr.brouillard.oss.jgitver.metadata.Metadatas;
import fr.brouillard.oss.jgitver.metadata.TagType;

public abstract class VersionStrategy<T extends VersionStrategy> {
    private VersionNamingConfiguration vnc;
    private Repository repository;
    private Git git;
    private MetadataRegistrar registrar;

    protected MetadataRegistrar getRegistrar() {
        return registrar;
    }

    /**
     * Default constructor.
     * @param vnc the configuration to use
     * @param repository the git repository
     * @param git a git helper object built from the repository
     * @param registrar a storage for found/calculated metadata
     */
    public VersionStrategy(VersionNamingConfiguration vnc, Repository repository, Git git, MetadataRegistrar registrar) {
        this.vnc = vnc;
        this.repository = repository;
        this.git = git;
        this.registrar = registrar;
    }

    /**
     * Build a version using the given information extracted from the git repository.
     * 
     * @param head cannot be null the current head commit
     * @param parents a non null list of commits that will be involved in version naming. 
     *      The list cannot be null and contains the first commit of the repository if no commit with version tag can be found.
     * @return a non null Version object
     * @throws VersionCalculationException in case an error occurred while computing the version 
     */
    public abstract Version build(Commit head, List<Commit> parents) throws VersionCalculationException;

    /**
     * Defines the history commit depth, starting from HEAD, until which parent commits will be parsed to find tags
     * information. This parameter is informative and will be respected only if at least one commit with version
     * information is found between HEAD and the defined depth. If none is found then the search will go deeper until it
     * find one commit with version information or until it reaches the first commit. Valid only when the {@link #searchMode()}
     * is {@link VersionStrategy.StrategySearchMode#DEPTH}.
     * 
     * @return a strict positive integer representing the depth until which the search will stop.
     */
    public int searchDepthLimit() {
        return Integer.MAX_VALUE;
    }
    
    public boolean considerTagAsAVersionOne(Ref tag) {
        String tagName = tagNameFromRef(tag);
        return getVersionNamingConfiguration().getSearchPattern().matcher(tagName).matches();
    }

    public StrategySearchMode searchMode() {
        return StrategySearchMode.STOP_AT_FIRST;
    }

    private String tagNameFromRef(Ref tag) {
        return tag.getName().replace("refs/tags/", "");
    }

    protected TagType computeTagType(Ref tagToUse, Ref annotatedTag) {
        if (annotatedTag != null) {
            if (tagToUse.getObjectId().toString().equals(annotatedTag.getObjectId().toString())) {
                return TagType.ANNOTATED;
            }
        }
        return TagType.LIGHTWEIGHT;
    }

    protected T runAndGetSelf(Runnable runnable) {
        runnable.run();
        return self();
    }

    protected T self() {
        return (T) this;
    }

    protected Version getBaseVersionAndRegisterMetadata(Commit base, Ref tagToUse) {
        Version baseVersion = Version.DEFAULT_VERSION;

        if (tagToUse != null) {
            String tagName = GitUtils.tagNameFromRef(tagToUse);
            TagType tagType = computeTagType(tagToUse, maxVersionTag(base.getAnnotatedTags()).orElse(null));
            baseVersion = tagToVersion(tagName);

            getRegistrar().registerMetadata(Metadatas.BASE_TAG_TYPE, tagType.name());
            getRegistrar().registerMetadata(Metadatas.BASE_TAG, tagName);
        }

        getRegistrar().registerMetadata(Metadatas.BASE_VERSION, baseVersion.toString());
        getRegistrar().registerMetadata(Metadatas.CURRENT_VERSION_MAJOR, Integer.toString(baseVersion.getMajor()));
        getRegistrar().registerMetadata(Metadatas.CURRENT_VERSION_MINOR, Integer.toString(baseVersion.getMinor()));
        getRegistrar().registerMetadata(Metadatas.CURRENT_VERSION_PATCH, Integer.toString(baseVersion.getPatch()));

        return baseVersion;
    }

    protected Version tagToVersion(String tagName) {
        return Version.parse(getVersionNamingConfiguration().extractVersionFrom(tagName));
    }

    protected boolean isGitDirty() {
        return Lambdas.unchecked(GitUtils::isDirty).apply(getGit());
    }

    protected Ref findTagToUse(Commit head, Commit base) {
        return isBaseCommitOnHead(head, base) && !isGitDirty()
                ? maxVersionTag(base.getAnnotatedTags(), base.getLightTags())
                : maxVersionTag(base.getLightTags(), base.getAnnotatedTags());
    }

    protected Commit findVersionCommit(Commit head, List<Commit> parents) {
        return parents.size() > 1 ? findMaxVersionCommit(head, parents) : parents.get(0);
    }

    protected Commit findMaxVersionCommit(Commit head, List<Commit> parents) {
        return parents.stream()
                .map(commit -> toVersionTarget(head, commit))
                .max(Comparator.naturalOrder())
                .map(VersionTarget::getTarget)
                .orElse(parents.get(0));
    }

    protected Ref maxVersionTag(List<Ref> primaryTags, List<Ref> secondaryTags) {
        return maxVersionTag(primaryTags).orElseGet(() -> maxVersionTag(secondaryTags).orElse(null));
    }

    protected Optional<Ref> maxVersionTag(List<Ref> tags) {
        return tags.stream()
                .map(this::toVersionTarget)
                .max(Comparator.naturalOrder())
                .map(VersionTarget::getTarget);
    }

    protected VersionTarget<Ref> toVersionTarget(Ref tagRef) {
        String tagName = GitUtils.tagNameFromRef(tagRef);
        return new VersionTarget<>(tagToVersion(tagName), tagRef);
    }

    protected VersionTarget<Commit> toVersionTarget(Commit head, Commit commit) {
        String tagName = GitUtils.tagNameFromRef(findTagToUse(head, commit));
        Version version = Version.parse(tagName);
        return new VersionTarget<>(version, commit);
    }

    public static enum StrategySearchMode {
        /**
         * Search will stop on first commit having at least one tag with version information.
         */
        STOP_AT_FIRST,
        /**
         * Search go deep in the git commit history tree to find all relevant commits having at least one tag with
         * version information. The search will respect {@link VersionStrategy#searchDepthLimit()} defined value.
         */
        DEPTH;
    }

    protected VersionNamingConfiguration getVersionNamingConfiguration() {
        return vnc;
    }

    protected Repository getRepository() {
        return repository;
    }

    protected Git getGit() {
        return git;
    }

    protected boolean isBaseCommitOnHead(Commit head, Commit base) {
        return head.getGitObject().name().equals(base.getGitObject().name());
    }

    protected Version enhanceVersionWithBranch(Version baseVersion, String branch) {
        getRegistrar().registerMetadata(Metadatas.BRANCH_NAME, branch);

        // let's add a branch qualifier if one is computed
        Optional<String> branchQualifier = getVersionNamingConfiguration().branchQualifier(branch);
        if (branchQualifier.isPresent()) {
            getRegistrar().registerMetadata(Metadatas.QUALIFIED_BRANCH_NAME, branchQualifier.get());
            baseVersion = baseVersion.addQualifier(branchQualifier.get());
        }
        return baseVersion;
    }

    private static class VersionTarget<T> implements Comparable<VersionStrategy.VersionTarget<T>> {
        private final Version version;
        private final T target;

        VersionTarget(Version version, T target) {
            this.version = version;
            this.target = target;
        }

        Version getVersion() {
            return version;
        }

        T getTarget() {
            return target;
        }

        @Override
        public int compareTo(VersionTarget versionTarget) {
            return this.version.compareTo(versionTarget.version);
        }
    }
}
