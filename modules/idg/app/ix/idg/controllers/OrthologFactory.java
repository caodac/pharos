package ix.idg.controllers;

import java.util.*;
import play.db.ebean.*;
import play.mvc.Result;

import ix.core.NamedResource;
import ix.idg.models.Ortholog;
import ix.core.controllers.EntityFactory;

@NamedResource(name="ortholog",type=Ortholog.class)
public class OrthologFactory extends EntityFactory {
    static final public Model.Finder<Long, Ortholog> finder = 
        new Model.Finder(Long.class, Ortholog.class);

    public static Ortholog getOrtholog (Long id) {
        return getEntity (id, finder);
    }

    public static List<Ortholog> getOrthologs
        (int top, int skip, String filter) {
        return filter (new FetchOptions (top, skip, filter), finder);
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
        return edits (id, Ortholog.class);
    }

    public static Result doc (Long id) {
        return doc (id, finder);
    }

    public static Result get (Long id, String expand) {
        return get (id, expand, finder);
    }

    public static Result field (Long id, String path) {
        return field (id, path, finder);
    }

    public static Result create () {
        return create (Ortholog.class, finder);
    }

    public static Result delete (Long id) {
        return delete (id, finder);
    }

    public static Result update (Long id, String field) {
        return update (id, field, Ortholog.class, finder);
    }
}
