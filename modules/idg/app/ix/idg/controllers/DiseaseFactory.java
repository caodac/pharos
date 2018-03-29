package ix.idg.controllers;

import ix.core.NamedResource;
import ix.idg.models.Disease;
import ix.idg.models.Target;
import ix.core.controllers.EntityFactory;
import com.avaje.ebean.Expr;
import com.avaje.ebean.RawSql;
import com.avaje.ebean.RawSqlBuilder;
import com.avaje.ebean.Query;
import play.db.ebean.Model;
import play.mvc.Result;
import play.Logger;

import java.util.List;

@NamedResource(name="diseases",type=Disease.class)
public class DiseaseFactory extends EntityFactory {
    static final public Model.Finder<Long, Disease> finder = 
        new Model.Finder(Long.class, Disease.class);

    public static Disease getDisease (Long id) {
        return getEntity (id, finder);
    }

    // return targets for a given disease
    public static List<Target> getTargets (Long id) {
        RawSql sql = RawSqlBuilder.parse
            ("select d.id,d.name,d.description,d.idg_family,d.idg_tdl "
             +" from ix_core_xref a,ix_idg_disease b, ix_idg_disease_link c,"
             +"ix_idg_target d where ix_idg_disease_id=b.id "
             +"and ix_core_xref_id = a.id and a.kind = 'ix.idg.models.Target' "
             +"and d.id = a.refid and b.id="+id)
            .columnMapping("d.id", "id")
            .columnMapping("d.idg_tdl", "idgTDL")
            .columnMapping("d.idg_family", "idgFamily")
            .columnMapping("d.name", "name")
            .columnMapping("d.description", "description")
            .create();
        return TargetFactory.finder.setRawSql(sql).findList();
    }

    public static List<Disease> getDiseases(int top, int skip, String filter) {
        return filter(new FetchOptions(top, skip, filter), finder);
    }

    public static Result count () {
        return count (finder);
    }
    public static Result page (int top, int skip) {
        return page (top, skip, null);
    }
    public static Result page (int top, int skip, String filter) {
        return page (top, skip, filter, finder);
    }

    public static Result edits (Long id) {
        return edits (id, Disease.class);
    }

    public static Result get (Long id, String expand) {
        return get (id, expand, finder);
    }

    public static Result resolve (String name, String expand) {
        return resolve (Expr.or(Expr.eq("synonyms.term", name),
                                Expr.eq("name", name)),
                        expand, finder);
    }

    public static Result field (Long id, String path) {
        return field (id, path, finder);
    }

    public static Result create () {
        return create (Disease.class, finder);
    }

    public static Result delete (Long id) {
        return delete (id, finder);
    }

    public static Result update (Long id, String field) {
        return update (id, field, Disease.class, finder);
    }

    public static Disease registerIfAbsent (String name) {
        List<Disease> diseases = finder.where().eq("name", name).findList();
        if (diseases.isEmpty()) {
            Disease dis = new Disease ();
            dis.name = name;
            dis.save();
            return dis;
        }
        return diseases.iterator().next();
    }
}
