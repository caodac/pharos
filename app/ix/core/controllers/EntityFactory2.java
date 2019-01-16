package ix.core.controllers;

import com.avaje.ebean.Expression;
import com.avaje.ebean.Query;
import play.Logger;
import play.db.ebean.Model;

/**
 * Created by katzelda on 11/5/18.
 */
public abstract class EntityFactory2<K,T> {

    private final Model.Finder<K,T> finder;

    public EntityFactory2(Model.Finder<K, T> finder) {
        this.finder = finder;
    }

    protected abstract Expression resolverExpressionFilter(String name);

    public T get(K id){
        return finder.byId(id);
    }
    public T resolve(String name, String expand){
        if (expand != null && !"".equals(expand)) {
            Query<T> query = finder.query();

            StringBuilder path = new StringBuilder ();
            for (String p : expand.split("\\.")) {
                if (path.length() > 0)
                    path.append('.');
                path.append(p);
                Logger.debug("  -> fetch "+path);
                query = query.fetch(path.toString());
            }

            return query.where(resolverExpressionFilter(name))
                    .setMaxRows(1).findUnique();

        }
        else {
            return finder.where(resolverExpressionFilter(name))
                    .setMaxRows(1).findUnique();

        }
    }

}
