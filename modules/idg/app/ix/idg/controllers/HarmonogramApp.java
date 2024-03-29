package ix.idg.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ix.core.models.Keyword;
import ix.core.plugins.IxCache;
import ix.core.search.TextIndexer;
import ix.idg.models.HarmonogramCDF;
import ix.idg.models.Target;
import ix.ncats.controllers.App;
import ix.utils.Util;
import play.Logger;
import play.mvc.Result;
import play.twirl.api.Content;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.Callable;

public class HarmonogramApp extends App {

    static ObjectMapper mapper = new ObjectMapper();

    public static Result error(int code, String mesg) {
        return ok(ix.idg.views.html.error.render(code, mesg));
    }

    public static Result _notFound(String mesg) {
        return notFound(ix.idg.views.html.error.render(404, mesg));
    }

    public static Result _badRequest(String mesg) {
        return badRequest(ix.idg.views.html.error.render(400, mesg));
    }

    public static Result _internalServerError(Throwable t) {
        t.printStackTrace();
        return internalServerError
                (ix.idg.views.html.error.render
                        (500, "Internal server error: " + t.getMessage()));
    }


    public static Result view(String q, final String ctx) {
        if (q == null && ctx == null)
            return _badRequest("Must specify a comma separated list of Uniprot ID's or a target search context");
        String[] accs = new String[0];
        if (q != null) accs = q.split(",");
        // if cache key is specified, this takes precedence over query string
        if (ctx != null) {
            try {
                TextIndexer.SearchResult result =
                        getOrElse(ctx, new Callable<TextIndexer.SearchResult>() {
                            public TextIndexer.SearchResult call() throws Exception {
                                return null;
                            }
                        });
                if (result == null)
                    return _notFound("No cache entry for key: " + ctx);
                List matches = result.getMatches();
                List<String> sq = new ArrayList<>();
                for (Object o : matches) {
                    if (o instanceof Target) sq.add(IDGApp.getId((Target) o));
                }
                accs = sq.toArray(new String[]{});
                Logger.debug("Got " + accs.length
                        + " targets from cache using key: " + ctx);
            } catch (Exception e) {
                return _internalServerError(e);
            }
        }
        return (ok(ix.idg.views.html.harmonogram.render(accs, ctx)));
    }

    public static Result hgForTarget(String q,
                                     final String ctx,
                                     final String format,
                                     final String type) {
        if (ctx != null) {
            TextIndexer.SearchResult result =
                    (TextIndexer.SearchResult) IxCache.get(ctx);
            if (result != null) {
                StringBuilder sb = new StringBuilder();
                for (Object obj : result.getMatches()) {
                    if (obj instanceof Target) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(IDGApp.getId((Target) obj));
                    }
                }

                // override whatever specified in q
                q = sb.toString();
            }
        }

        return _handleHgRequest(q, format, type);
    }

    static Result _handleHgRequest(final String q, final String format, final String type) {
        if (q == null) return _badRequest("Must specify one or more targets via the q query parameter");
        try {
            final String key = "hg/" + q + "/" + format + "/" + Util.sha1(request());
            return getOrElse(key, new Callable<Result>() {
                public Result call() throws Exception {
                    if (type != null && type.toLowerCase().contains("radar"))
                        return _hgForRadar(q, type);
                    if (q.contains(",")) return _hgForTargets(q.split(","), format);
                    return _hgForTargets(new String[]{q}, format);
                }
            });
        } catch (Exception e) {
            return ok ("{}").as("application/json");
        }
    }

    static String _hgmapToTsv(Map<String, Map<String, HarmonogramCDF>> map, String[] header) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sym");
        for (String aHeader : header) sb.append("\t").append(aHeader);
        sb.append("\n");

        for (String asym : map.keySet()) {
            sb.append(asym).append("\t").append(_hgmapToTsvRow(map.get(asym), header));
            sb.append("\n");
        }
        return sb.toString();
    }

    static String _hgmapToTsvRow(Map<String, HarmonogramCDF> map, String[] keys) {
        StringBuilder sb = new StringBuilder();
        String delimiter = "";
        for (String akey : keys) {
            HarmonogramCDF cdf = map.get(akey);
            if (cdf == null)
                sb.append(delimiter).append(0); // TODO what is a good value ot indicate missingness?
            else
                sb.append(delimiter).append(map.get(akey).getCdf());
            delimiter = "\t";
        }
        return sb.toString();
    }

    static int getKeyIndex(Set<String> keys, String key) {
        int i = 0;
        for (String k : keys) {
            if (k.equals(key)) return i;
            i++;
        }
        return -1;
    }

    static ArrayNode arrayToArrayNode(Integer[] a) {
        ArrayNode node = mapper.createArrayNode();
        for (Integer elem : a) node.add(elem);
        return node;
    }

    private static Result dataSourceDump() throws Exception {


        final String key = "hg/ds/";
        Set<String> ds = getOrElse(key, new Callable<Set<String>>() {
            public Set<String> call() throws Exception {
                List<HarmonogramCDF> cdfs = HarmonogramFactory.finder.select("dataSource").findList();
                Set<String> ds = new HashSet<>();
                for (HarmonogramCDF cdf : cdfs)
                    ds.add(cdf.getDataSource() + "\t" + cdf.getDataSourceUrl() + "\t" + cdf.getAttrType() + "\t" + cdf.getAttrGroup() + "\t" + cdf.getDataType());
                return ds;
            }
        });

        Map<String, List<String[]>> attrType = new HashMap<String, List<String[]>>();
        Map<String, List<String[]>> attrGroup = new HashMap<String, List<String[]>>();
        Map<String, List<String[]>> dataType = new HashMap<String, List<String[]>>();

        for (String s : ds) {
            String[] toks = s.split("\t");
            String dataSource = toks[0];
            String dataUrl = toks[1];
            String atype = toks[2];
            String agroup = toks[3];
            String dtype = toks[4];

            // attr_type
            if (attrType.containsKey(atype)) {
                List<String[]> value = attrType.get(atype);
                value.add(new String[]{dataSource, dataUrl});
                attrType.put(atype, value);
            } else {
                List<String[]> value = new ArrayList<>();
                value.add(new String[]{dataSource, dataUrl});
                attrType.put(atype, value);
            }

            // attr_group
            if (attrGroup.containsKey(agroup)) {
                List<String[]> value = attrGroup.get(agroup);
                value.add(new String[]{dataSource, dataUrl});
                attrGroup.put(agroup, value);
            } else {
                List<String[]> value = new ArrayList<>();
                value.add(new String[]{dataSource, dataUrl});
                attrGroup.put(agroup, value);
            }
            
            // data_type
            if (dataType.containsKey(dtype)) {
                List<String[]> value = dataType.get(dtype);
                value.add(new String[]{dataSource, dataUrl});
                dataType.put(dtype, value);
            } else {
                List<String[]> value = new ArrayList<>();
                value.add(new String[]{dataSource, dataUrl});
                dataType.put(dtype, value);
            }

        }

        ArrayNode root = mapper.createArrayNode();
        root.addAll(_getDsArray(attrGroup, "attr_group"));
        root.addAll(_getDsArray(attrType, "attr_type"));
        root.addAll(_getDsArray(dataType, "data_type"));
        return (ok(root));
    }

    private static ArrayNode _getDsArray(Map<String, List<String[]>> map, String field) {
        ArrayNode root = mapper.createArrayNode();
        for (String k : map.keySet()) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("fieldName", field);
            obj.put("value", k);
            ArrayNode arr = mapper.createArrayNode();
            for (String[] s : map.get(k)) {
                ObjectNode o = mapper.createObjectNode();
                o.put("ds_name", s[0]);
                    o.put("ds_url", s[1]);
                arr.add(o);
            }
            obj.put("ds", arr);
            root.add(obj);
        }
        return root;
    }



    public static Result dataSources(String field, final String value) throws Exception {
        if (field == null && value == null) return dataSourceDump();

        if (field == null || field.trim().equals("") || value == null || value.trim().equals(""))
            return _badRequest("Must specify a field name and value");

        final String fieldName = field.split("-")[1];
        final String key = "hg/ds/" + fieldName + "/" + value;
        Set<String> ds = getOrElse(key, new Callable<Set<String>>() {
            public Set<String> call() throws Exception {
                List<HarmonogramCDF> cdfs = HarmonogramFactory.finder.select("dataSource").where().eq(fieldName, value).findList();
                Set<String> ds = new HashSet<>();
                for (HarmonogramCDF cdf : cdfs)
                    ds.add(cdf.getDataSource() + "\t" + cdf.getDataSourceUrl());
                return ds;
            }
        });
        ObjectNode root = mapper.createObjectNode();
        root.put("fieldName", fieldName);
        root.put("value", value);
        ArrayNode arr = mapper.createArrayNode();
        for (String s : ds) {
            ObjectNode o = mapper.createObjectNode();
            String[] toks = s.split("\t");
            o.put("ds_name", toks[0]);
            if (toks.length == 2)
                o.put("ds_url", toks[1]);
            else o.put("ds_url", "");
            arr.add(o);
        }
        root.put("ds", arr);
        return ok(root);
    }

    static String getId(Target t) {
        Keyword kw = t.getSynonym(Commons.UNIPROT_ACCESSION);
        return kw != null ? kw.term : null;
    }

    static List<Target> getTargets (final String theQuery) {
        List<Target> ts;
        switch (theQuery) {
        case "tdark":
            ts = TargetFactory.finder
                .where().eq("idgTDL", Target.TDL.Tdark).findList();
            break;
        case "tclin":
            ts = TargetFactory.finder
                .where().eq("idgTDL", Target.TDL.Tclin).findList();
            break;
        case "tchem":
            ts = TargetFactory.finder
                .where().eq("idgTDL", Target.TDL.Tchem).findList();
                    break;
        case "tbio":
            ts = TargetFactory.finder
                .where().eq("idgTDL", Target.TDL.Tbio).findList();
            break;
        case "gpcr":
            ts = TargetFactory.finder
                .where().eq("idgFamily", "GPCR").findList();
            break;
        case "ogpcr":
            ts = TargetFactory.finder
                .where().eq("idgFamily", "oGPCR").findList();
            break;
        case "kinase":
            ts = TargetFactory.finder
                .where().eq("idgFamily", "Kinase").findList();
            break;
        case "ic":
            ts = TargetFactory.finder
                .where().eq("idgFamily", "Ion Channel").findList();
            break;
        case "nr":
            ts = TargetFactory.finder
                .where().eq("idgFamily", "Nuclear Receptor").findList();
            break;
        default:
            ts = new ArrayList<>();
            break;
        }
        return ts;
    }   

    // only valid for single target
    public static Result _hgForRadar (final String q, final String type)
        throws Exception {
        if (q == null || q.contains(",") || !type.contains("-"))
            return _badRequest
                ("Must specify a single Uniprot ID and/or type must be "
                 +"of the form radar-XXX");
        
        final String key = "hgForRadar/"+q+"/"+type;
        Content content = getOrElse (key, new Callable<Content>() {
                public Content call () throws Exception {
                    JsonNode json = _hgForRadarJson (q, type);
                    return json != null ? CachableContent.wrap(json) : null;
                }
            });
        
        return content != null ? ok (content)
            : ok("{}").as("application/json");
    }
    
    public static JsonNode _hgForRadarJson (final String q, String type)
        throws Exception {

        final String fieldName = type.split("-")[1];

        // Lets get unique list of field values
        final String key = "hg/radar/distinct-" + fieldName;
        List<String> fieldValues = getOrElse(key, new Callable<List<String>>() {
            public List<String> call() throws Exception {
                List<String> fieldValues = new ArrayList<>();
                Connection conn = play.db.DB.getConnection();
                PreparedStatement pst = conn.prepareStatement("select distinct " + fieldName + " from ix_idg_harmonogram order by " + fieldName);
                ResultSet rset = pst.executeQuery();
                while (rset.next()) fieldValues.add(rset.getString(1));
                rset.close();
                pst.close();
                conn.close();
                return fieldValues;
            }
        });

        final String theQuery = q.toLowerCase(); // to check for the special cases: all, tdark, tclin, etc
        List<HarmonogramCDF> hgs;
        int ntarget = 1;
        if (theQuery.equals("all")) {
            hgs = HarmonogramFactory.finder.all();
            ntarget = TargetFactory.finder.findRowCount();
        } else if (("tdark tclin tchem tbio gpcr kinase ionchannel nuclearreceptor ogpcr").contains(theQuery)) {
            List<Target> ts = getOrElse
                (HarmonogramApp.class.getName()+".hgForRadar.targets/"+theQuery,
                 new Callable<List<Target>> () {
                     public List<Target> call () {
                         return getTargets (theQuery);
                     }
                 });
            
            ntarget = ts.size();
            final List<String> uniprots = new ArrayList<>();
            for (Target t : ts) uniprots.add(getId(t));
            hgs = getOrElse
                (HarmonogramApp.class.getName()
                 +"/"+Util.sha1(uniprots.toArray(new String[0])),
                 new Callable<List<HarmonogramCDF>> () {
                     public List<HarmonogramCDF> call () { 
                         return HarmonogramFactory.finder
                         .where().in("uniprotId", uniprots).findList();
                     }
                 });
        } else {
            hgs = getOrElse
                (HarmonogramApp.class.getName()+"/"+Util.sha1(q),
                 new Callable<List<HarmonogramCDF>> () {
                     public List<HarmonogramCDF> call () {
                         return HarmonogramFactory.finder
                         .where().in("uniprotId", q).findList();
                     }
                 });
        }
        Logger.debug("Working with " + hgs.size() + " CDF's from " + ntarget + " targets for q = " + q);

        HashMap<String, Double> attrMap = new HashMap<>();
        HashMap<String, Integer> countMap = new HashMap<>();
        for (HarmonogramCDF hg : hgs) {
            String akey = hg.getAttrGroup();
            switch (fieldName) {
                case "attr_type":
                    akey = hg.getAttrType();
                    break;
                case "data_type":
                    akey = hg.getDataType();
                    break;
                case "attr_group":
                    akey = hg.getAttrGroup();
                    break;
            }
            Double value;
            if (attrMap.containsKey(akey)) {
                value = attrMap.get(akey) + hg.getCdf();
                countMap.put(akey, countMap.get(akey)+1);
            } else {
                value = hg.getCdf();
                countMap.put(akey, 1);
            }
            attrMap.put(akey, value);
        }

        // convert CDF sums to means (which should scale to 0-1)
        for (String akey : countMap.keySet()) {
            attrMap.put(akey, attrMap.get(akey) / ((double) countMap.get(akey).intValue()));
        }

        // need to scale in case we were aggregating over target sets (all, Tdark, Tclin, etc)
        double factor = 1.0/ntarget;
        for (String mkey : attrMap.keySet()) {
            Double val = attrMap.get(mkey);
            val *= factor;
            attrMap.put(mkey, val);
        }

//        HashMap<String, Double> attrMap = getOrElse(theQuery, new Callable<HashMap<String, Double>>() {
//            @Override
//            public HashMap<String, Double> call() throws Exception {
//                return attrMap;
//            }
//        });
        if (attrMap.isEmpty()) {
            return null;
        }

        ArrayNode anode = mapper.createArrayNode();
        for (String axis : fieldValues) {
            ObjectNode onode = mapper.createObjectNode();
            onode.put("axis", axis);
            if (attrMap.containsKey(axis))
                onode.put("value", attrMap.get(axis));
            else
                onode.put("value", 0.0);
            anode.add(onode);
        }
        ObjectNode container = mapper.createObjectNode();
        container.put("className", q);
        container.put("axes", anode);
        ArrayNode root = mapper.createArrayNode();
        root.add(container);
        
        return root;
    }

    public static Result _hgForTargets (final String[] accs,
                                        final String format) throws Exception {
        final String key = "hgForTargets/"+Arrays.hashCode(accs)+"/"+format;
        Content content = getOrElse (key, new Callable<Content> () {
                public Content call () throws Exception {
                    return App.CachableContent.wrap
                       (_hgForTargetsContent (accs, format));
                }
            });
        return content != null ? ok (content)
            : ok("{}").as("application/json");
    }
    
    public static Content _hgForTargetsContent
        (String[] accs, String format) throws Exception {
        List<HarmonogramCDF> hg = new ArrayList<>();
        for (String a : accs) {
            List<HarmonogramCDF> cdf = HarmonogramFactory.finder
                .where().eq("uniprotId", a).findList();
            hg.addAll(cdf);
        }
        
        if (hg.isEmpty()) {
            return null;
        }

        Map<String, Map<String, HarmonogramCDF>> allValues = new TreeMap<>();
        Set<String> colNames = new HashSet<>();
        for (HarmonogramCDF acdf : hg) {
            String sym = acdf.getSymbol();
            Map<String, HarmonogramCDF> values;
            if (!allValues.containsKey(sym)) values = new HashMap<>();
            else values = allValues.get(sym);
            values.put(acdf.getDataSource(), acdf);
            allValues.put(sym, values);
            colNames.add(acdf.getDataSource());
        }
        Logger.debug("Retrieved Harmonogram data for " + allValues.size() + " targets");

        // Arrange column names in a default ordering - needs to be updated
        String[] header = colNames.toArray(new String[]{});
        Arrays.sort(header);

        if (format != null && format.toLowerCase().equals("tsv")) {
            return new play.twirl.api.Txt(_hgmapToTsv(allValues, header));
        } else {

            // Construct data matrix for clustering
            int r = 0;
            Double[][] matrix = new Double[allValues.size()][header.length];
            for (String sym : allValues.keySet()) {
                Double[] row = new Double[header.length];
                Map<String, HarmonogramCDF> cdfs = allValues.get(sym);
                for (int i = 0; i < header.length; i++) {
                    HarmonogramCDF cdf = cdfs.get(header[i]);
                    row[i] = cdf == null ? null : cdf.getCdf();
                }
                matrix[r++] = row;
            }
            
            Logger.debug("Clustering harmanogram matrix "
                         +r+"x"+header.length+"...");
            long start = System.currentTimeMillis();
            HClust hc = new HClust();
            hc.setData(matrix, header, allValues.keySet().toArray(new String[]{}));
            hc.run();
            Logger.debug("Clustering completes in "
                         +String.format
                         ("%1$.3fs",1e-3*(System.currentTimeMillis()-start)));

            // construct the membership matrix, each column is cluster membership
            // for a given height. Each row is a target. So [i,j] indicates cluster
            // id for target i at the j'th height. Thus the group parameter in the
            // hgram json is simply the row of the matrix for that target
            double[] rowHeights = hc.getRowClusteringHeights();
            Integer[][] clusmem = new Integer[matrix.length][rowHeights.length];
            for (int i = 0; i < rowHeights.length; i++) {
                TreeMap<String, Integer> memberships = hc.getClusterMemberships(hc.rcluster, rowHeights[i]);
                int j = 0;
                for (String key : memberships.keySet()) clusmem[j++][i] = memberships.get(key);
            }


//            for (int i = 0; i < clusmem.length; i++) {
//                for (int j = 0; j < clusmem[0].length; j++) {
//                    System.out.print(clusmem[i][j]+" ");
//                }
//                System.out.println();
//            }

            ArrayNode rowNodes = mapper.createArrayNode();
            ArrayNode colNodes = mapper.createArrayNode();
            ArrayNode links = mapper.createArrayNode();

            int rank = 1;
            for (String sym : allValues.keySet()) {
                ObjectNode aRowNode = mapper.createObjectNode();
                Map<String, HarmonogramCDF> cdfs = allValues.get(sym);
                // get any CDF object for this symbol - they all have the same target info
                HarmonogramCDF acdf = cdfs.values().iterator().next();

                // extract the group vector
                int idx = getKeyIndex(allValues.keySet(), sym);
                aRowNode.put("group", arrayToArrayNode(clusmem[idx]));
                aRowNode.put("clust", clusmem[idx][0]);
                aRowNode.put("rank", rank++);
                aRowNode.put("name", sym);
//                aRowNode.put("cl", acdf.getIDGFamily());

                rowNodes.add(aRowNode);
            }

            // create a map of column name to data type
            Map<String, String> colNameTypeMap = new HashMap<>();
            for (String col : header) {
                for (String key : allValues.keySet()) {
                    Map<String, HarmonogramCDF> cdfs = allValues.get(key);
                    HarmonogramCDF cdf = cdfs.get(col);
                    if (cdf != null) {
                        colNameTypeMap.put(col, cdf.getDataType());
                        break;
                    }
                }
            }
            rank = 1;
            for (String aColName : header) {
                ObjectNode aColNode = mapper.createObjectNode();
                aColNode.put("name", aColName);
                aColNode.put("cluster", 1);
                aColNode.put("rank", rank++);
//                aColNode.put("cl", colNameTypeMap.get(aColName));
                colNodes.add(aColNode);
            }

            int row = 0;
            for (String sym : allValues.keySet()) {
                for (int col = 0; col < header.length; col++) {
                    HarmonogramCDF cdf = allValues.get(sym).get(header[col]);
                    ObjectNode node = mapper.createObjectNode();
                    node.put("source", row);
                    node.put("target", col);
                    node.put("value", cdf == null ? null : cdf.getCdf());
                    links.add(node);
                }
                row++;
            }

            ObjectNode root = mapper.createObjectNode();
            root.put("row_nodes", rowNodes);
            root.put("col_nodes", colNodes);
            root.put("links", links);
            
            return new play.twirl.api.JavaScript(root.toString());
        }
    }
}
