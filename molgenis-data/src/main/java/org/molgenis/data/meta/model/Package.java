package org.molgenis.data.meta.model;

import org.molgenis.data.Entity;
import org.molgenis.data.meta.MetaUtils;
import org.molgenis.data.support.StaticEntity;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.removeAll;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.molgenis.data.meta.model.PackageMetadata.*;

/**
 * Package defines the structure and attributes of a Package. Attributes are unique. Other software components can use
 * this to interact with Packages and/or to configure backends and frontends, including Repository instances.
 */
@SuppressWarnings("SpringJavaAutowiringInspection")
public class Package extends StaticEntity
{
	public static final String PACKAGE_SEPARATOR = "_";

	public Package(Entity entity)
	{
		super(entity);
	}

	/**
	 * Constructs a package with the given meta data
	 *
	 * @param entityType package meta data
	 */
	public Package(EntityType entityType)
	{
		super(entityType);
	}

	/**
	 * Constructs a package with the given type code and meta data
	 *
	 * @param packageId  package identifier (fully qualified package name)
	 * @param entityType language meta data
	 */
	public Package(String packageId, EntityType entityType)
	{
		super(entityType);
		setId(packageId);
	}

	public void setFullyQualifiedName()
	{
	}

	/**
	 * Copy-factory (instead of copy-constructor to avoid accidental method overloading to {@link #Package(EntityType)})
	 *
	 * @param package_ package
	 * @return deep copy of package
	 */
	public static Package newInstance(Package package_)
	{
		Package packageCopy = new Package(package_.getEntityType());
		packageCopy.setId(package_.getId());
		packageCopy.setName(package_.getName());
		packageCopy.setLabel(package_.getLabel());
		packageCopy.setDescription(package_.getDescription());
		Package parent = package_.getParent();
		packageCopy.setParent(parent != null ? Package.newInstance(parent) : null);
		Iterable<Tag> tags = package_.getTags();
		packageCopy.setTags(stream(tags.spliterator(), false).map(Tag::newInstance).collect(toList()));
		return packageCopy;
	}

	public String getId()
	{
		return getString(ID);
	}

	public Package setId(String id)
	{
		set(ID, id);
		return this;
	}

	/**
	 * Gets the name of the package without the trailing parent packages
	 *
	 * @return package name
	 */
	public String getName()
	{
		return getString(PackageMetadata.NAME);
	}

	public Package setName(String name)
	{
		set(PackageMetadata.NAME, name);
		return this;
	}

	/**
	 * Gets the parent package or null if this package does not have a parent package
	 *
	 * @return parent package or <tt>null</tt>
	 */
	public Package getParent()
	{
		return getEntity(PackageMetadata.PARENT, Package.class);
	}

	public Package setParent(Package parentPackage)
	{
		set(PackageMetadata.PARENT, parentPackage);
		return this;
	}

	/**
	 * Gets the subpackages of this package or an empty list if this package doesn't have any subpackages.
	 *
	 * @return sub-packages
	 */
	public Iterable<Package> getChildren()
	{
		return getEntities(CHILDREN, Package.class);
	}

	/**
	 * Gets the fully qualified name of this package
	 *
	 * @return fully qualified package name
	 */
	public String getFullyQualifiedName()
	{
		return MetaUtils.getFullyQualyfiedName(getName(), getParent());
	}

	/**
	 * The label of this package
	 *
	 * @return package label or <tt>null</tt>
	 */
	public String getLabel()
	{
		return getString(PackageMetadata.LABEL);
	}

	public Package setLabel(String label)
	{
		set(PackageMetadata.LABEL, label);
		return this;
	}

	/**
	 * The description of this package
	 *
	 * @return package description or <tt>null</tt>
	 */
	public String getDescription()
	{
		return getString(PackageMetadata.DESCRIPTION);
	}

	public Package setDescription(String description)
	{
		set(PackageMetadata.DESCRIPTION, description);
		return this;
	}

	/**
	 * Get all tags for this package
	 *
	 * @return package tags
	 */
	public Iterable<Tag> getTags()
	{
		return getEntities(PackageMetadata.TAGS, Tag.class);
	}

	/**
	 * Set tags for this package
	 *
	 * @param tags package tags
	 * @return this package
	 */
	public Package setTags(Iterable<Tag> tags)
	{
		set(PackageMetadata.TAGS, tags);
		return this;
	}

	/**
	 * Add a {@link Tag} to this {@link Package}
	 *
	 * @param tag
	 */
	public void addTag(Tag tag)
	{
		set(PackageMetadata.TAGS, concat(getTags(), singletonList(tag)));
	}

	/**
	 * Remove a {@link Tag} from this {@link Package}
	 *
	 * @param tag
	 */
	public void removeTag(Tag tag)
	{
		Iterable<Tag> tags = getTags();
		removeAll(tags, singletonList(tag));
		set(PackageMetadata.TAGS, tag);
	}

	/**
	 * Gets the entities in this package. Does not return entities referred to by sub-packages
	 *
	 * @return package entities
	 */
	public Iterable<EntityType> getEntityTypes()
	{
		return getEntities(ENTITY_TYPES, EntityType.class);
	}

	/**
	 * Get the root of this package, or itself if this is a root package
	 *
	 * @return root package of this package or <tt>null</tt>
	 */
	public Package getRootPackage()
	{
		Package package_ = this;
		while (package_.getParent() != null)
		{
			package_ = package_.getParent();
		}
		return package_;
	}

	@Override
	public String toString()
	{
		return "Package{" + "name=" + getFullyQualifiedName() + '}';
	}
}
