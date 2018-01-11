package ix.idg.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import ix.utils.Global;

import javax.persistence.*;
import ix.core.models.Indexable;
import ix.core.models.BeanViews;

@Entity
@Table(name = "ix_idg_ortholog")
public class Ortholog extends play.db.ebean.Model
    implements java.io.Serializable {
    @Id public Long id;
    
    @Indexable(facet=true,name="Ortholog Species")    
    public String species;
    public Long geneId;
    public String symbol;
    public String name;

    public Ortholog () {
    }
}
