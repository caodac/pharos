package ix.core.models;

import javax.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@DiscriminatorValue("MSH")
public class Mesh extends Value {
    public boolean majorTopic;

    @Indexable(taxonomy=true, suggest=true, name="MeSH")
    @Column(length=1024)
    public String heading;

    public Mesh () {
        super ("MeSH");
    }
    public Mesh (boolean majorTopic) {
        super ("MeSH");
        this.majorTopic = majorTopic;
    }
    public Mesh (String heading) {
        super ("MeSH");
        this.heading = heading;
    }

    @Override
    public String getValue () { return heading; }
}
