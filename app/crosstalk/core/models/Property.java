package crosstalk.core.models;

import play.db.ebean.Model;
import javax.persistence.*;
import com.fasterxml.jackson.annotation.JsonView;

@Entity
@Table(name="ct_core_property")
public class Property extends Model {
    @Id
    public Long id;

    public String name;
    public String type;

    @ManyToOne(cascade=CascadeType.ALL)
    public Resource resource;
    public String label; // label used for stitching

    public Property () {}
    public Property (String name, String type) {
        this.name = name;
        this.type = type;
    }
}
