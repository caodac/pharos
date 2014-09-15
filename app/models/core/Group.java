package models.core;

import java.util.List;
import java.util.ArrayList;
import play.db.ebean.Model;
import javax.persistence.*;

@Entity
@Table(name="ct_group")
public class Group extends Model {
    @Id
    public Long id;

    @Column(unique=true)
    public String name;

    @ManyToMany(cascade=CascadeType.ALL)
    @Basic(fetch=FetchType.EAGER)
    @JoinTable(name="ct_group_principal")
    public List<Principal> members = new ArrayList<Principal>();

    public Group () {}
    public Group (String name) {
        this.name = name;
    }
}
