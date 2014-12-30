package ix.core.models;

import play.db.ebean.Model;
import javax.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@DiscriminatorValue("KEY")
@DynamicFacet(label="label", value="term")
public class Keyword extends Value {
    @Column(length=255)
    @Indexable(facet=true, suggest=true, name="Keyword")
    public String term;
    @Lob
    public String url;

    public Keyword () {}
    public Keyword (String term) {
        this.term = term;
    }
    public Keyword (String label, String term) {
	super (label);
	this.term = term;
    }

    @Override
    public String getValue () { return term; }
}
