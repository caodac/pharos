package ix.idg.models;

import ix.core.models.Indexable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ix_idg_expression")
public class Expression extends play.db.ebean.Model 
    implements java.io.Serializable {
    public static final String EXPR_QUAL = "Expression Qualitative";

    @Id public Long id;

    @Column(nullable=false)
    public String tissue;

    @Column(nullable = false)
    public String source;

    @Column(nullable = false)
    public Long proteinId;

    public String sourceid;

    public Double confidence;
    public Double numberValue;

    @Column(nullable = true)
    @Indexable(facet=true,name="Qualitiative Expression Levels")
    public String qualValue;

    @Column(nullable = true)
    @Indexable(facet=true,sortable=true,name="Cell Type")
    public String cellType;

    public String evidence;
    public String uberonid;

    public Expression() {
    }

    public String getCellType() { return cellType; }

    public Long getProteinId() {
        return proteinId;
    }

    public String getSourceid() {
        return sourceid;
    }

    public String getSource() {
        return source;
    }

    public String getTissue() {
        return tissue;
    }

    public Double getConfidence() {
        return confidence;
    }

    public Double getNumberValue() {
        return numberValue;
    }

    public String getQualValue() {
        return qualValue;
    }

    public String getEvidence() {
        return evidence;
    }

    public Expression(String tissue, Double confidence, Double numberValue, String qualValue, String evidence) {
        this.tissue = tissue;
        this.confidence = confidence;
        this.numberValue = numberValue;
        this.qualValue = qualValue;
        this.evidence = evidence;
    }


    @Override
    public String toString() {
        return "Expression{source='"+source+"' sourceId='"+sourceid+"' tissue='"+tissue+"' qualitative="+qualValue+" number="+numberValue+"}";
    }
}
