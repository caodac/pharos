package ix.core.models;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.db.ebean.Model;
import javax.persistence.*;

@Entity
@Table(name="ix_core_payload")
public class Payload extends Model {
    @Id public UUID id;
    @Version public Long version;

    @ManyToOne(cascade=CascadeType.ALL)
    public Namespace namespace;
    public final Date created = new Date ();
    
    @Column(length=255)
    public String name;
    public String filename;

    @Column(length=40)
    public String sha1;
    
    @Column(length=128)
    public String mimeType; // mime type
    @Column(name="filesize")
    public Long size;

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_core_payload_property")
    public List<Value> properties = new ArrayList<Value>();
    
    public Payload () {}
    public Value addIfAbsent (Value prop) {
        if (prop != null) {
            if (prop.id != null) 
                for (Value p : properties) {
                    if (p.id.equals(prop.id))
                        return p;
                }
            properties.add(prop);
        }
        return prop;
    }
}
