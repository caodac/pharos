package ix.idg.controllers;

import ix.idg.models.Target;

public interface Commons {
    public static final String SOURCE = "Data Source";
    public static final String COLLECTION = "Collection";
    public static final String LIBRARY = "Library";
    
    public static final String IDG_DISEASE = "IDG Disease";
    public static final String IDG_DEVELOPMENT = Target.IDG_DEVELOPMENT;
    public static final String IDG_FAMILY = Target.IDG_FAMILY;
    public static final String IDG_DRUG = "IDG Drug";
    public static final String IDG_ZSCORE = "IDG Z-score";
    public static final String IDG_CONF = "IDG Confidence";
    public static final String IDG_EVIDENCE = "IDG Evidence";
    public static final String IDG_GENERIF = "IDG GeneRIF";
    public static final String IDG_TARGET = "IDG Target";
    public static final String IDG_SMILES = "IDG SMILES";
    public static final String IDG_TISSUE = "IDG Tissue";
    public static final String IDG_EXPR = "IDG Expression";    
    public static final String IDG_LIGAND = "IDG Ligand";
    public static final String IDG_TISSUE_REF = "IDG Tissue Ref";
    public static final String IDG_TOOLS = "IDG Tools";
    public static final String IDG_RESOURCES = "IDG Resources";
    public static final String IDG_TOOLS_ANTIBODIES = "Antibodies";
    public static final String IDG_TOOLS_SELECTIVE_COMPOUNDS =
        "ChEMBL Selective Compound";
    public static final String IDG_TOOLS_PHENOTYPES = "Phenotypes";
    public static final String IDG_PUBLICATIONS = "Publications";
    
    public static final String TARGET_COMPARTMENTS = "Compartments";
    public static final String TARGET_PPI = "Protein-Protein Interactions";
    
    public static final String PHARMALOGICAL_ACTION = "Pharmalogical Action";
    public static final String PUBMED_ID = "PubMed ID";
    public static final String PDB_ID = "PDB ID";
    public static final String LIGAND_SOURCE = "Ligand Source";
    public static final String LIGAND_ACTIVITY = "Ligand Activity";
    public static final String LIGAND_ACTIVITY_SOURCE =
        "Ligand Activity Source";
    public static final String LIGAND_DRUG = "Ligand Drug";
    public static final String LIGAND_COUNT = "Ligand Count";
    public static final String IUPHAR_SYNONYM = "IUPHAR Synonym";
    
    public static final String GRANT_FUNDING_IC = "Grant Funding IC";
    public static final String GRANT_ACTIVITY = "Grant Activity";
    public static final String GRANT_APPLICATION = "Grant Application";

    public static final String TECHDEV_PI = "TechDev PI";
    public static final String TECHDEV_GRANT = "TechDev Grant";

    public static final String COMPARTMENT_GOTERM = "Compartment GO Term";
    public static final String COMPARTMENT_EVIDENCE = "Compartment Evidence";
    public static final String COMPARTMENT_TYPE = "Compartment Type";

    public static final String ORTHOLOG_SPECIES = "Ortholog Species";
    public static final String OMIM_GENE = "OMIM Gene";
    public static final String GWAS_TRAIT = "GWAS Trait";
    public static final String IMPC_TERM = "IMPC Term";
    public static final String MGI_TERM = "MGI Term";
    public static final String OMIM_TERM = "OMIM Term";
    
    public static final String REACTOME_REF = "Reactome Pathway Ref";

    public static final String UBERON_TISSUE = "UBERON Tissue";
    public static final String GTEx_TISSUE = "GTEx Tissue";
    public static final String GTEx_EXPR = "GTEx Expression";
    public static final String HPM_TISSUE = "HPM Tissue";
    public static final String HPM_EXPR = "HPM Expression";
    public static final String HPA_RNA_TISSUE = "HPA RNA Tissue";
    public static final String HPA_RNA_EXPR = "HPA RNA Expression";
    public static final String HPA_PROTEIN_TISSUE = "HPA Protein Tissue";
    public static final String HPA_PROTEIN_CELL_TYPE = "HPA Protein Cell Type";
    public static final String HPA_PROTEIN_EXPR = "HPA Protein Expression";
    public static final String JENSEN_TM_EXPR = "Jensen-TM Expression";
    public static final String JENSEN_TM_TISSUE = "Jensen-TM Tissue";
    public static final String JENSEN_KB_EXPR = "Jensen-KB Expression";
    public static final String JENSEN_KB_TISSUE = "Jensen-KB Tissue";
    public static final String HCA_RNA_CELL_LINE = "HCA RNA Cell Line";
    public static final String CSPA_CELL_LINE = "CSPA Cell Line";

    public static final String KEGG_PATHWAY = "KEGG Pathway";
    public static final String PATENT_COUNT = "Patent Count";
    public static final String PPI_COUNT = "PPI Count";

    public static final String GO_ID = "GO ID";
    public static final String GO_TERM = "GO Term";
    public static final String GO_COMPONENT = "GO Component";
    public static final String GO_PROCESS = "GO Process";
    public static final String GO_FUNCTION = "GO Function";

    public static final String UBERON_TISSUE_CLASS = "UBERON Tissue Class";
    public static final String UBERON_TISSUE_ANCESTRY =
        "UBERON Tissue Ancestry";
    
    public static final String PANTHER_PROTEIN_CLASS = "PANTHER Protein Class";
    public static final String PANTHER_PROTEIN_ANCESTRY =
        "PANTHER Protein Ancestry";

    public static final String PROTEIN_FEATURE = "Protein Feature";

    public static final String DTO_ID = "DTO ID";
    public static final String DTO_PROTEIN_CLASS = "DTO Protein Class";
    public static final String DTO_PROTEIN_ANCESTRY = "DTO Protein Ancestry";
    
    public static final String TINX_NOVELTY = "TINX Novelty";
    public static final String TINX_IMPORTANCE = "TINX Importance";
    public static final String TINX_DISEASE_NOVELTY = "TINX Disease Novelty";
    public static final String TINX_PUBLICATION = "TINX Publication";

    public static final String WHO_ATC = "WHO ATC";
    public static final String ATC_ANCESTRY =  "ATC Ancestry";
    
    public static final String ChEMBL = "CHEMBL";
    public static final String ChEMBL_ID = "CHEMBL ID";
    public static final String ChEMBL_MECHANISM = "CHEMBL Mechanism";
    public static final String ChEMBL_MOA_MODE = "CHEBML MOA Mode";
    public static final String ChEMBL_MECHANISM_COMMENT = "CHEMBL Mechanism Comment";
    public static final String ChEMBL_SYNONYM = "CHEMBL Synonym";
    public static final String ChEMBL_MOLFILE = "CHEMBL Molfile";
    public static final String ChEMBL_INCHI = "CHEMBL InChI";
    public static final String ChEMBL_INCHI_KEY = "CHEMBL InChI Key";
    public static final String ChEMBL_SMILES = "CHEMBL Canonical SMILES";
    public static final String ChEMBL_PROTEIN_CLASS = "CHEMBL Protein Class";
    public static final String ChEMBL_PROTEIN_ANCESTRY =
        "CHEMBL Protein Ancestry";
    public static final String ChEMBL_ACTIVITY_ID = "CHEMBL Activity ID";
    public static final String ChEMBL_MOLREGNO = "CHEMBL Molregno";

    public static final String DRUGCENTRAL = "DrugCentral";
    public static final String DRUGCENTRAL_ID = "DrugCentral ID";
    public static final String PUBCHEM_CID = "PubChem CID";

    public static final String DISGENET_DISEASE = "DisGeNET Disease";
    public static final String DISGENET_SOURCE = "DisGeNET Source";
    
    public static final String UNIPROT_ACCESSION = "UniProt Accession";
    public static final String UNIPROT_GENE = "UniProt Gene";
    public static final String UNIPROT_DISEASE = "UniProt Disease";
    public static final String UNIPROT_DISEASE_RELEVANCE =
        "UniProt Disease Relevance";
    public static final String UNIPROT_TARGET = "UniProt Target";    
    public static final String UNIPROT_KEYWORD = "UniProt Keyword";
    public static final String UNIPROT_ORGANISM = "UniProt Organism";
    public static final String UNIPROT_SHORTNAME = "UniProt Shortname";
    public static final String UNIPROT_FULLNAME = "UniProt Fullname";
    public static final String UNIPROT_NAME = "UniProt Name";
    public static final String UNIPROT_TISSUE = "UniProt Tissue";
    public static final String UNIPROT_EXPR = "UniProt Expression";
    public static final String UNIPROT_SEQUENCE = "UniProt Sequence";

    public static final String SEQUENCE_FEATURE = "Sequence Feature";
    public static final String ENTREZ_GENE = "Entrez Gene";
    public static final String STRING_ID = "STRINGDB ID";

    public static final String MLP_ASSAY = "MLP Assay";
    public static final String MLP_ASSAY_TYPE = "MLP Assay Type";
    public static final String MLP_ASSAY_ACTIVE = "MLP Assay Active";
    public static final String MLP_ASSAY_INACTIVE = "MLP Assay Inactive";
    public static final String MLP_ASSAY_INCONCLUSIVE = "MLP Assay Inconclusive";
    public static final String MLP_ASSAY_AID = "MLP Assay PubChem ID";

    public static final String URL_ANTIBODYPEDIA = "Antibodypedia.com URL";
}
