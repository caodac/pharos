package ix.idg.controllers;

import com.avaje.ebean.Expr;
import com.avaje.ebean.Expression;
import ix.core.controllers.EntityFactory2;
import ix.idg.models.Target;
import play.db.ebean.Model;

/**
 * Created by katzelda on 11/5/18.
 */
public class TargetFactory2 extends EntityFactory2<Long, Target> {

    public TargetFactory2(Model.Finder<Long, Target> finder) {
        super(finder);
    }

    @Override
    protected Expression resolverExpressionFilter(String name) {
        return Expr.or(Expr.or(Expr.or(Expr.eq("synonyms.term", name),
                Expr.eq("name", name)),
                Expr.eq("accession", name)),
                Expr.eq("gene", name));
    }

}
