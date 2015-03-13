package ix.ginas.models.v1;

import ix.ginas.models.utils.JSONEntity;

import java.util.List;
import java.util.Map;
import javax.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonProperty;

import ix.core.models.Indexable;
import ix.core.models.Keyword;
import ix.ginas.models.Ginas;


@JSONEntity(title = "Note", isFinal = true)
@Entity
@Table(name="ix_ginas_note")
public class Note extends Ginas {
    @JSONEntity(title = "Note")
    @Lob
    @Basic(fetch=FetchType.EAGER)
    public String note;

    public Note () {}
    public Note (String note) {
        this.note = note;
    }
}