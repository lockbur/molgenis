package org.molgenis.data.version;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.DatabaseMetaDataCallback;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Get the MetaData version the database is built with and the current MetaData version.
 * 
 * The current version is stored in the molgenis-server.properties with key 'meta.data.version'.
 * 
 * <p>
 * If this key is not present, we're either looking at a molgenis 1.4.3 or a new install should be run. We'll check the
 * datasource for the presence of a mysql entities table. If no <code>MolgenisUser</code> table exists in the
 * datasource's database, a new install is assumed, so the version will be set to the current version and no upgrade
 * will take place.
 * </p>
 * 
 * <p>
 * This is done so we can upgrade the database. If we store it in the database we must access the database to get it but
 * we must upgrade before we can access the database...
 * </p>
 */
@Service
public class MetaDataVersionService
{
	public static final int CURRENT_META_DATA_VERSION = 5;
	private static final String META_DATA_VERSION_KEY = "meta.data.version";

	private static final Logger LOG = LoggerFactory.getLogger(MetaDataVersionService.class);

	@Autowired
	public MetaDataVersionService(DataSource dataSource)
	{
		if (getMolgenisServerProperties().getProperty(META_DATA_VERSION_KEY) == null)
		{
			LOG.warn("No {} property found in molgenis-server.properties.", META_DATA_VERSION_KEY);
			if (isPopulatedDatabase(dataSource))
			{
				updateToVersion(0);
			}
			else
			{
				updateToCurrentVersion();
			}
		}
	}

	/**
	 * Checks if this is a populated database.
	 */
	public boolean isPopulatedDatabase(DataSource dataSource)
	{
		if (dataSource == null)
		{
			LOG.warn("No datasource found");
			return false;
		}
		try
		{
			return (boolean) JdbcUtils.extractDatabaseMetaData(dataSource, new DatabaseMetaDataCallback()
			{

				@Override
				public Object processMetaData(DatabaseMetaData dbmd) throws SQLException, MetaDataAccessException
				{
					ResultSet tables = dbmd.getTables(null, null, "MolgenisUser", new String[]
					{ "TABLE" });
					boolean resultRow = tables.first();
					LOG.info("Table MolgenisUser {}found.", resultRow ? "" : "not ");
					return resultRow;
				}
			});
		}
		catch (MetaDataAccessException e)
		{
			return false;
		}
	}

	/**
	 * Get the molgenis meta data version where the database is generated with.
	 */
	public int getDatabaseMetaDataVersion()
	{
		return Integer.parseInt(getMolgenisServerProperties().getProperty(META_DATA_VERSION_KEY));
	}

	public void updateToCurrentVersion()
	{
		updateToVersion(CURRENT_META_DATA_VERSION);
	}

	public void updateToVersion(int version)
	{
		Properties properties = getMolgenisServerProperties();
		properties.setProperty(META_DATA_VERSION_KEY, Integer.toString(version));

		try (OutputStream out = new FileOutputStream(getMolgenisServerPropertiesFile()))
		{
			properties.store(out, "Molgenis server properties");
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public Properties getMolgenisServerProperties()
	{
		try (InputStream in = new FileInputStream(getMolgenisServerPropertiesFile()))
		{
			Properties p = new Properties();
			p.load(in);

			return p;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private File getMolgenisServerPropertiesFile()
	{
		// get molgenis home directory
		String molgenisHomeDir = System.getProperty("molgenis.home");
		if (molgenisHomeDir == null)
		{
			throw new IllegalArgumentException("missing required java system property 'molgenis.home'");
		}

		return new File(molgenisHomeDir, "molgenis-server.properties");
	}
}
