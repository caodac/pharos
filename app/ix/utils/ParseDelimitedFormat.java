package ix.utils;

import java.util.*;
import java.io.*;
import java.util.function.Consumer;

public class ParseDelimitedFormat {
    static public class Row {
        public final String[] header;
        public final String[] row;
        public final int line;

        Row (String[] header, String[] row, int line) {
            this.header = header;
            this.row = row;
            this.line = line;
        }

        public Map<String, String> data () {
            Map<String, String> data  = null;
            if (header != null) {
                data = new TreeMap<>();
                for (int i = 0; i < header.length; ++i)
                    data.put(header[i], row[i]);
            }
            return data;
        }
    }
    
    final boolean hasHeader;
    final char delimiter;
    
    public ParseDelimitedFormat () {
        this (false, ',');
    }

    public ParseDelimitedFormat (boolean hasHeader) {
        this (hasHeader, ',');
    }
    
    public ParseDelimitedFormat (boolean hasHeader, char delimiter) {
        this.hasHeader = hasHeader;
        this.delimiter = delimiter;
    }
    
    public int parse (InputStream is, Consumer<Row> consumer)
        throws IOException {
        int line = 0;
        try (BufferedReader br =
             new BufferedReader (new InputStreamReader (is))) {
            boolean quote = false;
            StringBuilder buf = new StringBuilder ();
            List<String> toks = new ArrayList<>();
            String[] header = null;
            for (int r; (r = br.read()) != -1; ) {
                char ch = (char)(r & 0xffff);
                if (ch == delimiter) {
                    if (quote) {
                        buf.append(ch);
                    }
                    else {
                        toks.add(buf.length() == 0 ? null : buf.toString());
                        buf.setLength(0);
                    }
                }
                else if (ch == '"') {
                    quote = !quote;
                }
                else if (ch == '\n' || ch == '\r') {
                    if (toks.isEmpty()) {
                    }
                    else if (!quote) {
                        ++line;
                        //toks.add(buf.length() == 0 ? null : buf.toString());
                        //System.out.println(line+": "+toks);
                        
                        Row row = null;
                        if (hasHeader && line == 1) {
                            header = toks.toArray(new String[0]);
                            row = new Row (header, null, line);
                        }
                        else {
                            row = new Row (header, toks.toArray(new String[0]),
                                           line);
                        }
                        consumer.accept(row);
                        buf.setLength(0);
                        toks.clear();
                    }
                    else {
                        buf.append(ch);
                    }
                }
                else {
                    buf.append(ch);
                }
            }

            if (!toks.isEmpty()) {
                ++line;
                
                Row row = null;
                if (hasHeader && line == 1) {
                    header = toks.toArray(new String[0]);
                    row = new Row (header, null, line);
                }
                else {
                    row = new Row (header, toks.toArray(new String[0]),
                                   line);
                }
                consumer.accept(row);
            }
        }
        
        return line;
    }

    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("Usage: ix.utils.ParseDelimitedFormat FILES...");
            System.exit(1);
        }

        for (String a : argv) {
            ParseDelimitedFormat pdf = new ParseDelimitedFormat (true, '\t');
            pdf.parse(new FileInputStream (a), r -> {
                    if (r.line > 1) {
                        System.out.println("------- "+r.line);
                        for (int i = 0; i < r.header.length; ++i) {
                            System.out.println(r.header[i]+": "+r.row[i]);
                        }
                    }
                    else {
                        System.out.println("------- header -------");
                        for (int i = 0; i < r.header.length; ++i) {
                            System.out.println(i+": "+r.header[i]);
                        }
                    }
                });
        }
    }
}
