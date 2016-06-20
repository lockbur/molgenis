package org.molgenis.ontology.core.meta;

import static java.util.Objects.requireNonNull;
import static org.molgenis.MolgenisFieldTypes.BOOL;
import static org.molgenis.MolgenisFieldTypes.TEXT;
import static org.molgenis.data.meta.EntityMetaData.AttributeRole.ROLE_ID;
import static org.molgenis.data.meta.EntityMetaData.AttributeRole.ROLE_LABEL;
import static org.molgenis.data.meta.Package.PACKAGE_SEPARATOR;
import static org.molgenis.ontology.core.model.OntologyPackage.PACKAGE_ONTOLOGY;

import org.molgenis.data.meta.SystemEntityMetaData;
import org.molgenis.ontology.core.model.OntologyPackage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OntologyTermNodePathMetaData extends SystemEntityMetaData
{
	public static final String SIMPLE_NAME = "OntologyTermNodePath";
	public final static String ONTOLOGY_TERM_NODE_PATH = PACKAGE_ONTOLOGY + PACKAGE_SEPARATOR + SIMPLE_NAME;

	public final static String ID = "id";
	public final static String ONTOLOGY_TERM_NODE_PATH_ATTR = "nodePath";
	public final static String ROOT = "root";

	private OntologyPackage ontologyPackage;

	public OntologyTermNodePathMetaData()
	{
		super(SIMPLE_NAME, PACKAGE_ONTOLOGY);
	}

	@Override
	public void init()
	{
		setPackage(ontologyPackage);
		addAttribute(ID, ROLE_ID).setVisible(false);
		addAttribute(ONTOLOGY_TERM_NODE_PATH_ATTR, ROLE_LABEL).setDataType(TEXT).setNillable(false);
		addAttribute(ROOT).setDataType(BOOL).setNillable(false);
	}

	@Autowired
	public void setOntologyPackage(OntologyPackage ontologyPackage)
	{
		this.ontologyPackage = requireNonNull(ontologyPackage);
	}
}