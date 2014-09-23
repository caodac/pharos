package crosstalk.core.models;

import play.db.ebean.Model;
import javax.persistence.*;

@Entity
@Table(name="ct_core_principal")
public class Principal extends Model {
    @Id
    public Long id;

    public String username;
    public String email;
    public boolean admin = false;

    @Column(length=1024)
    public String uri; // can be email or any unique uri

    @Column(length=256,unique=true)
    public String pkey; // private key

    @ManyToOne(cascade=CascadeType.ALL)
    public Figure selfie;

    public Principal () {}
    public Principal (boolean admin) {
        this.admin = admin;
    }
    public Principal (String email) {
        this.email = email;
    }
    public Principal (boolean admin, String email) {
        this.admin = admin;
        this.email = email;
    }
    public Principal (String username, String email) {
        this.username = username;
        this.email = email;
    }

    public boolean isAdmin () { return admin; }
}
