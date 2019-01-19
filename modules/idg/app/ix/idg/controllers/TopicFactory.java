package ix.idg.controllers;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Callable;
import java.security.MessageDigest;

import play.db.ebean.Model;
import com.avaje.ebean.Expr;
import com.avaje.ebean.QueryIterator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ix.core.models.*;
import ix.idg.models.*;
import ix.utils.Util;
import ix.utils.Global;
import ix.core.plugins.IxCache;
import ix.core.controllers.IxController;

import play.Logger;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.BodyParser;

public class TopicFactory extends IxController {
    public static class Topic implements Serializable {
        public final String query;
        public final EntityModel entity;
        public final Map<String, List<EntityModel>> related =
            new TreeMap<>();

        protected Topic (String query, EntityModel entity) {
            this.query = query;
            this.entity = entity;
        }
    }
    
    final static Model.Finder<Long, Target> targets = TargetFactory.finder;
    final static Model.Finder<Long, Ligand> ligands = LigandFactory.finder;
    final static Model.Finder<Long, Disease> diseases = DiseaseFactory.finder;

    protected TopicFactory () {
    }

    static String cacheKey (String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("sha1");
            Arrays.stream(values)
                .map(v -> v.toUpperCase())
                .sorted()
                .forEach(v -> {
                        try {
                            md.digest(v.getBytes("utf8"));
                        }
                        catch (Exception ex) {
                            Logger.error("Can't convert to utf8", ex);
                        }
                    });
            
            return Util.toHex(md.digest());
        }
        catch (Exception ex) {
            Logger.error("Can't generate digest!", ex);   
        }
        return "deadbeef";
    }
    
    public static Topic[] getTopicsForTargets (String... names)
        throws Exception {
        List<Topic> topics = new ArrayList<>();
        for (String name : names) {
            Logger.debug("## getting topics for \""+name+"\"...");
            Target target = targets
                .fetch("links")
                .where(Expr.or(Expr.or(Expr.eq("synonyms.term", name),
                                       Expr.eq("accession", name)),
                               Expr.eq("gene", name)))
                .setMaxRows(1)
                .findUnique();
            if (target != null) {
                Topic t = new Topic (name, target);
                topics.add(t);
            }
        }
        
        return topics.toArray(new Topic[0]);
    }

    @BodyParser.Of(value = BodyParser.Text.class, maxLength = 100000)
    public static Result getTopics (String kind) {
        if (request().body().isMaxSizeExceeded()) {
            String type = request().getHeader("Content-Type");
            if (!"text/plain".equals(type))
                return badRequest ("Invalid Content-Type: "+type
                                   +"; only \"text/plain\" is allowed!");
            return badRequest ("Input is too large!");
        }

        try {
            String text = request().body().asText();
            ObjectNode json = null;
            if ("target".equalsIgnoreCase(kind)
                || "targets".equalsIgnoreCase(kind)) {
                String[] names = text.split("[;\\s,]");
                final String key = cacheKey (names);
                Topic[] topics = IxCache.getOrElse
                    (key, new Callable<Topic[]> () {
                            public Topic[] call () throws Exception {
                                return getTopicsForTargets (names);
                            }
                        }, 0);

                json = Json.newObject();
                json.put("etag", key);
                json.put("kind", "topic");
                json.put("query", Json.toJson(names));
                json.put("content", toJson (topics));
            }
            else {
                Logger.warn("Topic for "+kind+" currently not supported!");
            }

            return ok (json);
        }
        catch (Exception ex) {
            Logger.error("Can't generate topics", ex);
            return internalServerError
                (ix.idg.views.html.error.render
                 (500, "Internal server error: "+ex.getMessage()));
        }
    }

    public static JsonNode toJson (Topic... topics) {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode nodes = mapper.createArrayNode();
        for (Topic t : topics) {
            ObjectNode tn = mapper.createObjectNode();
            
            tn.put("id", t.entity.id);
            tn.put("kind", t.entity.getClass().getName());
            if (t.entity instanceof Target) {
                Target tar = (Target)t.entity;
                tn.put("accession", tar.accession);
                tn.put("gene", tar.gene);
                tn.put("tdl", Json.toJson(tar.idgTDL));
                tn.put("family", tar.idgFamily);
            }
            tn.put("name", t.entity.getName());
            tn.put("uri", Global.getRef(t.entity));

            ObjectNode n = mapper.createObjectNode();
            n.put("query", t.query);
            n.put("target", tn);
            
            List<XRef> ligands = new ArrayList<>();
            List<XRef> diseases = new ArrayList<>();
            for (XRef xref : t.entity.getLinks()) {
                if (Ligand.class.getName().equals(xref.kind))
                    ligands.add(xref);
                else if (Disease.class.getName().equals(xref.kind))
                    diseases.add(xref);
            }
            
            tn.put("ligand_count", ligands.size());
            if (!ligands.isEmpty()) {
                ArrayNode ln = mapper.createArrayNode();
                for (XRef xref : ligands) {
                    Ligand ligand = (Ligand) xref.deRef();
                    ObjectNode l = mapper.createObjectNode();
                    l.put("id", ligand.id);
                    l.put("kind", ligand.getClass().getName());
                    l.put("name", ligand.getName());
                    l.put("uri", Global.getRef(ligand));
                    
                    for (Value v : ligand.getProperties()) {
                        l.put(v.getLabel().replaceAll("\\s", "_"),
                              Json.toJson(v.getValue()));
                    }
                    
                    for (Value v : xref.getProperties()) {
                        l.put(v.getLabel().replaceAll("\\s", "_"),
                              Json.toJson(v.getValue()));
                    }
                    
                    for (XRef r : ligand.getLinks()) {
                        if (Structure.class.getName().equals(r.kind)) {
                            Structure struc = (Structure) r.deRef();
                            l.put("image",
                                  ix.ncats.controllers.routes.App.structure
                                  (struc.getId(), "svg", 200, null).toString());
                        }
                    }
                    ln.add(l);
                }
                n.put("ligands", ln);
            }
            
            tn.put("disease_count", diseases.size());
            if (!diseases.isEmpty()) {
                ArrayNode dn = mapper.createArrayNode();
                for (XRef xref : diseases) {
                    Disease disease = (Disease) xref.deRef();
                    ObjectNode d = mapper.createObjectNode();
                    d.put("id", disease.id);
                    d.put("kind", disease.getClass().getName());
                    d.put("name", disease.getName());
                    d.put("uri", Global.getRef(disease));
                    for (Value v : disease.getProperties())
                        d.put(v.getLabel().replaceAll("\\s", "_"),
                              Json.toJson(v.getValue()));
                    for (Value v : xref.getProperties()) {
                        d.put(v.getLabel().replaceAll("\\s", "_"),
                              Json.toJson(v.getValue()));
                    }
                    dn.add(d);
                }
                n.put("diseases", dn);
            }
            nodes.add(n);
        }
        return nodes;
    }
}
