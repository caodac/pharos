package ix.ncats.models.clinical;

import play.db.ebean.Model;
import javax.persistence.*;

import ix.core.models.Indexable;

@Entity
@Table(name="ix_ncats_clinical_studydesign")
public class StudyDesign extends Model {
    @Id public Long id;
    @Indexable(facet=true,name="Study Allocation")
    public String allocation;
    @Indexable(facet=true,name="Study Intervention Model")
    public String interventionModel;
    @Indexable(facet=true,name="Study Primary Purpose")
    public String primaryPurpose; 
    @Indexable(facet=true,name="Study Masking")
    public String masking;

    public StudyDesign () {}
}
