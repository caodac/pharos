package ix.ginas.models.v1;

import java.util.Arrays;
import java.util.UUID;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import javax.persistence.*;

import play.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import ix.core.models.Indexable;
import ix.core.models.Principal;
import ix.core.models.Keyword;
import ix.core.models.BeanViews;
import ix.core.models.Publication;
import ix.core.models.Value;
import ix.core.models.XRef;
import ix.ginas.models.utils.JSONEntity;
import ix.ginas.models.*;
import ix.utils.Global;

@JSONEntity(name = "substance", title = "Substance")
@Entity
@Table(name="ix_ginas_substance")
@Inheritance
@DiscriminatorValue("SUB")
public class Substance extends Ginas {
    /**
     * sigh.. can we be at least case-consistent?
     */
    public enum SubstanceClass {
        chemical,
        protein,
        nucleicAcid,
        polymer,
        structurallyDiverse,
        mixture,
        specifiedSubstanceG1,
        specifiedSubstanceG2,
        specifiedSubstanceG3,
        specifiedSubstanceG4,
        unspecifiedSubstance,   
        concept,
        reference
    }
    
    @JSONEntity(title = "Substance Type", values = "JSONConstants.ENUM_SUBSTANCETYPES", isRequired = true)
    @Indexable(suggest=true,facet=true, name="Substance Class")
    @Column(name="class")
    public SubstanceClass substanceClass;
    
    @Indexable(suggest=true,facet=true, name="Status")
    public String status;
    
    public String approvedBy;
    
    @JsonDeserialize(using=DateDeserializer.class)
    public Date approved;

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_substance_access")
    @JsonSerialize(using = PrincipalListSerializer.class)
    @JsonDeserialize(using = PrincipalListDeserializer.class)
    public List<Principal> access = new ArrayList<Principal>();
    
    @JSONEntity(title = "Names", minItems = 1, isRequired = true)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_substance_name")
    @JsonView(BeanViews.Full.class)
    public List<Name> names = new ArrayList<Name>();
    
    // TOOD original schema has superfluous name = codes in the schema here and
    // in all of Code's properties
    @JSONEntity(title = "Codes")
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_substance_code")
    @JsonView(BeanViews.Full.class)
    public List<Code> codes = new ArrayList<Code>();

    @OneToOne(cascade=CascadeType.ALL)
    public Modifications modifications;
    
    @JSONEntity(title = "Notes")
    @ManyToMany(cascade=CascadeType.ALL)    
    @JoinTable(name="ix_ginas_substance_note")
    @JsonView(BeanViews.Full.class)
    public List<Note> notes = new ArrayList<Note>();
    
    @JSONEntity(title = "Properties")
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_substance_prop")
    @JsonView(BeanViews.Full.class)
    public List<Property> properties = new ArrayList<Property>();
    
    @JSONEntity(title = "Relationships")
    @ManyToMany(cascade=CascadeType.ALL)    
    @JoinTable(name="ix_ginas_substance_rel")
    @JsonView(BeanViews.Full.class)
    public List<Relationship> relationships = new ArrayList<Relationship>();
    
    @JSONEntity(title = "References", minItems = 1, isRequired = true)
    @ManyToMany(cascade=CascadeType.ALL)    
    @JoinTable(name="ix_ginas_substance_ref")
    @JsonView(BeanViews.Full.class)
    public List<Reference> references = new ArrayList<Reference>();
    
    @JSONEntity(title = "Approval ID", isReadOnly = true)
    @Column(length=10)
    @Indexable(facet=true,suggest=true,name="Approval ID")
    public String approvalID;
    
    // TODO in original schema, this field is missing its items: String
    @JSONEntity(title = "Tags", format = "table", isUniqueItems = true)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_ginas_substance_tag")
    @JsonSerialize(using=KeywordListSerializer.class)
    @JsonDeserialize(using=TagListDeserializer.class)
    public List<Keyword> tags = new ArrayList<Keyword>();

    @Transient
    protected transient ObjectMapper mapper = new ObjectMapper ();
    
    public Substance () {
        this (SubstanceClass.concept);
    }
    public Substance (SubstanceClass subcls) {
        substanceClass = subcls;
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_names")
    public JsonNode getJsonNames () {
        JsonNode node = null;
        if (!names.isEmpty()) {
            try {
                ObjectNode n = mapper.createObjectNode();
                n.put("count", names.size());
                n.put("href", Global.getRef(getClass (), uuid)+"/names");
                node = n;
            }
            catch (Exception ex) {
                ex.printStackTrace();
                // this means that the class doesn't have the NamedResource
                // annotation, so we can't resolve the context
                node = mapper.valueToTree(names);
            }
        }
        return node;
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_references")
    public JsonNode getJsonReferences () {
        JsonNode node = null;
        if (!references.isEmpty()) {
            try {
                ObjectNode n = mapper.createObjectNode();
                n.put("count", references.size());
                n.put("href", Global.getRef(getClass (), uuid)+"/references");
                node = n;
            }
            catch (Exception ex) {
                // this means that the class doesn't have the NamedResource
                // annotation, so we can't resolve the context
                node = mapper.valueToTree(references);
            }
        }
        return node;
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_codes")
    public JsonNode getJsonCodes () {
        JsonNode node = null;
        if (!codes.isEmpty()) {
            try {
                ObjectNode n = mapper.createObjectNode();
                n.put("count", codes.size());
                n.put("href", Global.getRef(getClass (), uuid)+"/codes");
                node = n;
            }
            catch (Exception ex) {
                // this means that the class doesn't have the NamedResource
                // annotation, so we can't resolve the context
                node = mapper.valueToTree(codes);
            }
        }
        return node;
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_relationships")
    public JsonNode getJsonRelationships () {
        JsonNode node = null;
        if (!relationships.isEmpty()) {
            try {
                ObjectNode n = mapper.createObjectNode();
                n.put("count", relationships.size());
                n.put("href", Global.getRef(getClass (), uuid)
                      +"/relationships");
                node = n;
            }
            catch (Exception ex) {
                // this means that the class doesn't have the NamedResource
                // annotation, so we can't resolve the context
                node = mapper.valueToTree(relationships);
            }
        }
        return node;
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_properties")
    public JsonNode getJsonProperties () {
        JsonNode node = null;
        if (!properties.isEmpty()) {
            try {
                ObjectNode n = mapper.createObjectNode();
                n.put("count", properties.size());
                n.put("href", Global.getRef(getClass (), uuid)
                      +"/properties");
                node = n;
            }
            catch (Exception ex) {
                // this means that the class doesn't have the NamedResource
                // annotation, so we can't resolve the context
                node = mapper.valueToTree(properties);
            }
        }
        return node;
    }
    
    
    @Column(length=1024)
    @Indexable(suggest=true,facet=true, name="Name")
    public String getName () {
        for (Name n : names){
            if(n.preferred){
                return n.name;
            }
        }
        return names.get(0).name;
    }

    @PrePersist
    @PreUpdate
    public void tidy () {
        // preform any validation prior to persistence
        List<Relationship> remove = new ArrayList<Relationship>();
        for (Relationship rel : relationships) {
            SubstanceReference ref = rel.relatedSubstance;
            if (ref != null && ref.refuuid == null) {
                // remove this relationship
                remove.add(rel);
            }
        }

        if (!remove.isEmpty()) {
            for (Relationship rel : remove)
                relationships.remove(rel);
            Logger.warn("Substance "+approvalID+" has "+remove.size()
                        +" invalid relationship(s)!");
        }
    }
    
    public boolean isNonSubstanceConcept(){
        if(this.substanceClass.toString().equals("concept")){
                return  !isSubstanceVariant();
        }
        return false;
    }
    public boolean isSubstanceVariant(){
        if(this.substanceClass.toString().equals("concept")){
                for(Relationship r:relationships){
                                if(r.type.equals("SUBSTANCE->SUB_CONCEPT")){
                                        return true;
                                }
                        }
        }
                return false;
    }
    
    /**
     * Returns parent substance concept record for substance variant concepts.
     * 
     * 
     * @return
     */
    public SubstanceReference getParentSubstanceReference(){
        for(Relationship r:relationships){
                        if(r.type.equals("SUBSTANCE->SUB_CONCEPT")){
                                return r.relatedSubstance;
                        }
                }
        return null;
    }
    
    public String getApprovalID(){
        if(approvalID!=null)return approvalID;
        SubstanceReference subRef=getParentSubstanceReference();
        if(subRef!=null){
                return subRef.approvalID;
        }
        return null;
    }
    public String getApprovalIDDisplay(){
        if(approvalID!=null)return approvalID;
        SubstanceReference subRef=getParentSubstanceReference();
        if(subRef!=null){
                return subRef.approvalID;
        }
        return null;
    }
    
    public List<SubstanceReference> getChildConceptReferences(){
        List<SubstanceReference> subConcepts = new ArrayList<SubstanceReference>();
        for(Relationship r:relationships){
                        if(r.type.equals("SUB_CONCEPT->SUBSTANCE")){
                                subConcepts.add(r.relatedSubstance);
                        }
                }
        return subConcepts;
    }
    public boolean hasChildConceptReferences(){
        if(getChildConceptReferences().size()>0){
                return true;
        }else{
                return false;
        }
    }
    public boolean hasModifications(){
        if(this.modifications!=null){
                if(this.modifications.agentModifications.size()>0 || this.modifications.physicalModifications.size()>0 || this.modifications.structuralModifications.size()>0){
                        return true;
                }
        }
                return false;
        
    }
    public int getModificationCount(){
        int ret=0;
        if(this.modifications!=null){
                ret+=this.modifications.agentModifications.size();
                ret+=this.modifications.physicalModifications.size();
                ret+=this.modifications.structuralModifications.size();
        }
        return ret;
    }
    public Modifications getModifications(){
        return modifications;
    }
}
