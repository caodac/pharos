package crosstalk.core.models;

import java.util.Date;
import play.db.ebean.Model;
import javax.persistence.*;

import com.fasterxml.jackson.annotation.JsonView;

/**
 * Curation is simply a marker that indicates an Entity
 * has been *validated* by curator. This is different than
 * the Edit history of an entity.
 */
@Entity
@Table(name="ct_core_curation")
public class Curation extends Model {
    public enum Status {
        Pending,
            Rejected,
            Accepted
    }

    @Id
    public Long id;

    @ManyToOne(cascade=CascadeType.ALL)
    public Principal curator;
    public Status status;

    public final Date timestamp = new Date ();

    public Curation () {}
    public Curation (Principal curator) {
        this (curator, Status.Pending);
    }
    public Curation (Principal curator, Status status) {
        this.curator = curator;
        this.status = status;
    }
}

