package ix.ginas.models.v1;

import ix.ginas.models.utils.JSONEntity;
import ix.ginas.models.utils.JSONConstants;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import javax.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonProperty;

import ix.core.models.Indexable;
import ix.core.models.Keyword;
import ix.ginas.models.Ginas;

@JSONEntity(title = "Name", isFinal = true)
@Entity
@Table(name="ix_ginas_name")
public class Name extends Ginas {
    @JSONEntity(title = "Name", isRequired = true)
    @Column(nullable=false)
    @Indexable(name="Name", suggest=true)
    public String name;
    
    @JSONEntity(title = "Name Type", format = JSONConstants.CV_NAME_TYPE, values = "JSONConstants.ENUM_NAMETYPE")
    public String type;
    
    @JSONEntity(title = "Domains", format = "table", itemsTitle = "Domain", itemsFormat = JSONConstants.CV_NAME_DOMAIN)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_name_domain",
               joinColumns=@JoinColumn
               (name="ix_ginas_name_domain_uuid",
               referencedColumnName="uuid")
    )
    public List<Keyword> domains = new ArrayList<Keyword>();
    
    @JSONEntity(title = "Languages", format = "table", itemsTitle = "Language", itemsFormat = JSONConstants.CV_LANGUAGE)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_name_language",
               joinColumns=@JoinColumn
               (name="ix_ginas_name_language_uuid",
               referencedColumnName="uuid")
    )
    public List<Keyword> languages = new ArrayList<Keyword>();
    
    @JSONEntity(title = "Naming Jurisdictions", format = "table", itemsTitle = "Jurisdiction", itemsFormat = JSONConstants.CV_JURISDICTION)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_name_jurisdiction",
               joinColumns=@JoinColumn
               (name="ix_ginas_name_jurisdiction_uuid",
               referencedColumnName="uuid")
    )
    public List<Keyword> nameJurisdiction = new ArrayList<Keyword>();
    
    @JSONEntity(title = "Naming Organizations", format = "table")
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_name_nameorg")
    public List<NameOrg> nameOrgs = new ArrayList<NameOrg>();
    
    @JSONEntity(title = "Preferred Term")
    public boolean preferred;

    public Name () {}
    public Name (String name) { this.name = name; }
}