package ix.ncats.models.clinical;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Calendar;

import play.db.ebean.Model;
import javax.persistence.*;

import ix.core.models.Indexable;
import ix.core.models.Organization;
import ix.core.models.Keyword;
import ix.core.models.Value;
import ix.core.models.Publication;
import ix.core.models.XRef;
import ix.core.models.BeanViews;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * based on definition from clinicaltrials.gov
 */
@Entity
@Table(name="ix_ncats_clinicaltrial")
public class ClinicalTrial extends Model {
    @Id public Long id;
    @Version public Long version;

    @Column(length=15,unique=true)
    public String nctId;
    @Column(length=1024)
    public String url;

    @Basic(fetch=FetchType.EAGER)
    @Lob
    public String title;

    @Basic(fetch=FetchType.EAGER)
    @Lob
    public String officialTitle;

    @Lob
    public String summary;
    @Lob
    public String description;

    @Indexable(facet=true,name="Clinical Sponsor")
    @Column(length=1024)
    public String sponsor;

    @Indexable(facet=true,name="Study Type")    
    public String studyType;
    
    @OneToOne(cascade=CascadeType.ALL)
    public StudyDesign studyDesign;

    @Indexable(sortable=true,facet=true,name="Clinical Start Date")
    public Date startDate;
    @Indexable(sortable=true,facet=true,name="Clinical Completion Date")
    public Date completionDate;
    @Indexable(sortable=true)
    public Date firstReceivedDate;
    @Indexable(sortable=true,facet=true,name="Clinical Last Changed Date")
    public Date lastChangedDate;
    @Indexable(sortable=true)
    public Date verificationDate;
    @Indexable(sortable=true,facet=true,name="Clinical Results Received Date")
    public Date firstReceivedResultsDate;

    @Indexable(facet=true,name="Clinical Results")
    public boolean hasResults;

    @Indexable(facet=true,name="Clinical Status")
    public String status;

    @Indexable(facet=true,name="Clinical Phase")
    public String phase;

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_keyword",
               joinColumns=@JoinColumn(name="ix_ncats_ct_keyword_id",
                                       referencedColumnName="id")
               )
    public List<Keyword> keywords = new ArrayList<>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_sponsor")
    public List<Organization> sponsors = new ArrayList<>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_intervention")
    public List<Intervention> interventions = new ArrayList<>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_condition")
    public List<Condition> conditions = new ArrayList<>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_outcome")
    public List<Outcome> outcomes = new ArrayList<>();

    @OneToOne(cascade=CascadeType.ALL)
    public Eligibility eligibility;

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_location")
    public List<Organization> locations = new ArrayList<>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_publication")
    public List<Publication> publications = new ArrayList<>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_property")
    public List<Value> properties = new ArrayList<>();
    
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ncats_ct_link")
    public List<XRef> links = new ArrayList<>();


    public ClinicalTrial () {}

    @JsonIgnore
    @Indexable(facet=true, name="Clinical Trial Duration")
    public Integer getTrialDuration () {
        Integer duration = null;
        if (startDate != null && completionDate != null) {
            Calendar start = Calendar.getInstance();
            start.setTime(startDate);
            Calendar end = Calendar.getInstance();
            end.setTime(completionDate);
            duration = end.get(Calendar.YEAR) - start.get(Calendar.YEAR);
        }
        return duration;
    }

    @JsonIgnore
    @Indexable(facet=true, name="Clinical Intervention Count")
    public Integer getInterventionCount () {
        return interventions.size();
    }
    
    @JsonIgnore
    @Indexable(facet=true, name="Clinical Condition Count")
    public Integer getConditionCount () {
        return conditions.size();
    }

    @JsonIgnore
    @Indexable(facet=true, name="Clinical Publication Count")
    public Integer getPublicationCount () {
        return publications.size();
    }

    @JsonIgnore
    @Indexable(facet=true, name="Clinical Location Count")
    public Integer getLocationCount () {
        return locations.size();
    }

    @JsonIgnore
    @Indexable(facet=true, name="Clinical Outcome Count")
    public Integer getOutcomeCount () {
        return outcomes.size();
    }
}
