package ix.idg.models;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;

import ix.utils.Global;

import javax.persistence.*;
import ix.core.models.XRef;
import ix.core.models.Indexable;
import ix.core.models.BeanViews;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "ix_idg_ortholog")
public class Ortholog extends play.db.ebean.Model
    implements java.io.Serializable {
    @Id public Long id;
    public String refid; // source id
    @Indexable(facet=true,name="Ortholog Species")    
    public String species;
    public Long geneId;
    public String symbol;
    public String name;
    public String url;

    @JsonView(BeanViews.Full.class)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_idg_ortholog_link")
    public List<XRef> links = new ArrayList<XRef>();

    @Transient
    protected ObjectMapper mapper = new ObjectMapper ();
    
    public Ortholog () {
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_links")
    public JsonNode getJsonLinks () {
        JsonNode node = null;
        if (!links.isEmpty()) {
            try {
                ObjectNode n = mapper.createObjectNode();
                n.put("count", links.size());
                n.put("href", Global.getRef(getClass (), id)+"/links");
                node = n;
            }
            catch (Exception ex) {
                node = mapper.valueToTree(links);
            }
        }
        return node;
    }
}
