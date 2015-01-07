package ix.idg.controllers;

import ix.core.controllers.SearchFactory;
import ix.core.models.Text;
import ix.core.models.Value;
import ix.core.models.XRef;
import ix.core.search.TextIndexer;
import ix.idg.models.Disease;
import ix.idg.models.Target;
import play.Logger;
import play.cache.Cache;
import play.mvc.Controller;
import play.mvc.Result;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;


public class IDGApp extends Controller {
    public static int[] paging (int rowsPerPage, int page, int total) {
	int max = (total+rowsPerPage-1)/rowsPerPage;
	if (page < 0 || page > max) {
	    throw new IllegalArgumentException ("Bogus page "+page);
	}
	
	int[] pages;
	if (max <= 10) {
	    pages = new int[max];
	    for (int i = 0; i < pages.length; ++i)
		pages[i] = i+1;
	}
	else if (page >= max-3) {
	    pages = new int[10];
	    for (int i = pages.length; --i >= 0; )
		pages[i] = max--;
	}
	else {
	    pages = new int[10];
	    int i = 0;
	    for (; i < 7; ++i)
		pages[i] = i+1;
	    if (page >= pages[i-1]) {
		// now shift
		pages[--i] = page;
		while (i-- > 0)
		    pages[i] = pages[i+1]-1;
	    }
	    pages[8] = max-1;
	    pages[9] = max;
	}
	return pages;
    }
    
    public static Result index () {
	return ok (ix.idg.views.html.index.render
		   ("Pharos: Illuminating the Druggable Genome"));
    }

    public static Result error (int code, String mesg) {
	return ok (ix.idg.views.html.error.render(code, mesg));
    }

    public static Result target (long id) {
	try {
	    Target t = TargetFactory.getTarget(id);
	    return ok (ix.idg.views.html.targetdetails.render(t));
	}
	catch (Exception ex) {
	    return internalServerError
		(ix.idg.views.html.error.render(500, "Internal server error"));
	}
    }

    static List<TextIndexer.Facet> getFacets
	(final Class kind, final int fdim) {
	try {
	    TextIndexer.SearchResult result =
		SearchFactory.search(kind, null, 0, 0, fdim, null);
	    return result.getFacets();
	}
	catch (IOException ex) {
	    Logger.trace("Can't retrieve facets for "+kind, ex);
	}
	return new ArrayList<TextIndexer.Facet>();
    }

    static TextIndexer.Facet[] filter (List<TextIndexer.Facet> facets,
				       String... names) {
	if (names == null || names.length == 0)
	    return facets.toArray(new TextIndexer.Facet[0]);
	
	Set<String> unique = new HashSet<String>();
	for (String n : names) {
	    unique.add(n);
	}
	
	List<TextIndexer.Facet> filtered = new ArrayList<TextIndexer.Facet>();
	for (TextIndexer.Facet f : facets) {
	    if (unique.contains(f.getName()))
		filtered.add(f);
	}
	return filtered.toArray(new TextIndexer.Facet[0]);
    }
    
    public static Result targets (int rows, int page) {	
	try {
	    TextIndexer.Facet[] facets = Cache.getOrElse
		("TargetFacets", new Callable<TextIndexer.Facet[]>() {
			public TextIndexer.Facet[] call () {
			    return filter (getFacets (Target.class, 20),
					   "IDG Classification",
					   "IDG Target Family",
					   "TCRD Disease",
					   "TCRD Drug"
					   //"MeSH",
					   //"Keyword"
					   );
			}
		    }, 3600);
	    
	    int total = TargetFactory.finder.findRowCount();
	    rows = Math.min(total, Math.max(1, rows));
	    int[] pages = paging (rows, page, total);
	    
	    List<Target> targets =
		TargetFactory.getTargets(rows, (page-1)*rows, null);
	    
	    return ok (ix.idg.views.html.targets.render
		       (page, rows, pages, facets, targets));
	}
	catch (Exception ex) {
	    return badRequest (ix.idg.views.html.error.render
			       (404, "Invalid page requested: "+page));
	}
    }

	public static Result disease(long id) {
		try {
			Disease d = DiseaseFactory.getDisease(id);
			for (XRef xref : d.links) {
				System.out.println(xref.refid + "/" + xref.kind + "/" + xref.deRef());
				List<Value> props = xref.properties;
				for (Value prop: props) {
					System.out.println("prop = " + prop);
					if (prop.getValue() instanceof Text) {
						Text text = (Text) prop.getValue();
						System.out.println("\t"+text.getText()+"/"+text.getValue());
					}
				}
				System.out.println();
			}
			return DiseaseFactory.create();
//			return ok(ix.idg.views.html.diseasedetails.render(t));
		} catch (Exception ex) {
			return internalServerError
					(ix.idg.views.html.error.render(500, "Internal server error"));
		}
	}

	public static Result diseases(int rows, int page) throws Exception {
//		TextIndexer.Facet[] facets = new TextIndexer.Facet[]{};
		TextIndexer.Facet[] facets = Cache.getOrElse("DiseaseFacets", new Callable<TextIndexer.Facet[]>() {
			public TextIndexer.Facet[] call() {
				return filter(getFacets(Disease.class, 20),
						"IDG Classification",
						"IDG Target Family",
						"MIM"
						//"MeSH",
						//"Keyword"
				);
			}
		}, 3600);
		int total = DiseaseFactory.finder.findRowCount();
		rows = Math.min(total, Math.max(1, rows));
		int[] pages = paging(rows, page, total);

		List<Disease> diseases =
				DiseaseFactory.getDiseases(rows, (page - 1) * rows, null);

		return ok(ix.idg.views.html.diseases.render(page, rows, pages, facets, diseases));
	}
}
