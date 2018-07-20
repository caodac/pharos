package ix.core.plugins;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.WriteResult;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.EventListener;
import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.DocumentChange;
//import com.google.api.gax.rpc.ApiStreamObserver;

import java.util.*;
import java.io.*;
import java.beans.*;

import play.Logger;
import play.Plugin;
import play.Application;

public class FirebaseStore extends Plugin {
    private final Application app;

    Firestore db;
    final PropertyChangeSupport pcs = new PropertyChangeSupport (this);
    
    public static class Doc implements Comparable<Doc> {
        final DocumentReference ref;
        final Map<String, Object> data;
        final DocumentSnapshot doc;
        
        Doc (DocumentReference ref) throws Exception {
            doc = ref.get().get();
            if (doc.exists())
                data = doc.getData();
            else
                data = new HashMap<>();
            this.ref = ref;            
        }
        public String getId () { return ref.getId(); }
        
        public Doc put (String name, Object value) {
            data.put(name, value);
            return this;
        }
        
        public Doc save () throws Exception {
            return save (false);
        }
        
        public Doc save (boolean merge) throws Exception {
            if (merge)
                ref.set(data, SetOptions.merge()).get();
            else
                ref.set(data).get(); // block
            return this;
        }

        public Date getCreateTime () {
            return new Date (doc.getCreateTime().toEpochMilli());
        }

        public Doc update () throws Exception {
            if (data.isEmpty())
                throw new RuntimeException ("Doc is empty; nothing to update!");
            ref.update(data).get();
            return this;
        }

        public int compareTo (Doc d) {
            return doc.getCreateTime().compareTo(d.doc.getCreateTime());
        }
    }

    public static class Store implements EventListener<QuerySnapshot> {
        final CollectionReference ref;
        final ListenerRegistration listener;
        final PropertyChangeSupport pcs;
        
        Store (PropertyChangeSupport pcs, Firestore db, String name) {
            ref = db.collection(name);
            listener = ref.addSnapshotListener(this);
            this.pcs = pcs;
        }

        Store (PropertyChangeSupport pcs, CollectionReference ref) {
            this.ref = ref;
            listener = ref.addSnapshotListener(this);
            this.pcs = pcs;
        }

        protected Doc document (DocumentReference dref) throws Exception {
            return new Doc (dref);
        }
        
        public Doc document () throws Exception {
            return document (ref.document());
        }

        public Doc document (String id) throws Exception {
            return document (ref.document(id));
        }

        public List<Doc> documents () {
            List<Doc> docs = new ArrayList<>();
            try {
                List<QueryDocumentSnapshot> qdocs =
                    ref.get().get().getDocuments();
                for (QueryDocumentSnapshot qds : qdocs) {
                    //Logger.debug("++ "+qds.getId()+" "+qds.getCreateTime());
                    docs.add(new Doc (ref.document(qds.getId())));
                }

                Collections.sort(docs);
            }
            catch (Exception ex) {
                throw new RuntimeException ("Unable to query documents", ex);
            }
            return docs;
        }

        public void onEvent (QuerySnapshot qs, FirestoreException ex) {
            if (ex != null) {
                Logger.error("Firestore error", ex);
                return;
            }

            for (DocumentChange dc : qs.getDocumentChanges()) {
                switch (dc.getType()) {
                case ADDED:
                    Logger.debug("++ Doc added: "+dc.getDocument().getId()
                                 +" "+dc.getDocument().getCreateTime());
                    if (pcs != null && pcs.hasListeners("doc-added")) {
                        try {
                            Doc doc = new Doc (dc.getDocument().getReference());
                            pcs.firePropertyChange("doc-added", null, doc);
                        }
                        catch (Exception exx) {
                            Logger.error("Can't retrieve doc "
                                         +dc.getDocument().getId(), exx);
                        }
                    }
                    break;
                    
                case MODIFIED:
                    Logger.debug("++ Doc updated: "+dc.getDocument().getId()
                                 +" "+dc.getDocument().getUpdateTime());
                    if (pcs != null && pcs.hasListeners("doc-updated")) {
                        try {
                            Doc doc = new Doc (dc.getDocument().getReference());
                            pcs.firePropertyChange("doc-updated", doc, doc);
                        }
                        catch (Exception exx) {
                            Logger.error("Can't retrieve doc "
                                         +dc.getDocument().getId(), exx);
                        }
                    }
                    break;
                    
                case REMOVED:
                    Logger.debug("++ Doc deleted: "+dc.getDocument().getId()
                                 +" "+dc.getDocument().getUpdateTime());
                    if (pcs != null && pcs.hasListeners("doc-deleted")) {
                        try {
                            Doc doc = new Doc (dc.getDocument().getReference());
                            pcs.firePropertyChange("doc-deleted", doc, null);
                        }
                        catch (Exception exx) {
                            Logger.error("Can't retrieve doc "
                                         +dc.getDocument().getId(), exx);
                        }
                    }
                    break;
                }
            }
        }

        public void shutdown () {
            listener.remove();
        }
    }
    
    public FirebaseStore (Application app) {
        this.app = app;
    }

    public void onStart () {
        String file = app.configuration()
            .getString("ix.admin.firebase.auth", null);
        Logger.info("Loading plugin "+getClass().getName()
                    +"... credentials="+file);
        if (file != null && !file.equals("")) {
            try {
                FirebaseApp fapp = null;
                if (FirebaseApp.getApps().isEmpty()) {
                    GoogleCredentials credentials = GoogleCredentials.
                        fromStream(new FileInputStream (app.getFile(file)));
                    
                    String uid = app.configuration()
                        .getString("ix.admin.firebase.uid", null);
                    Map<String, Object> auth = null;
                    if (uid != null) {
                        auth = new HashMap<>();
                        auth.put("uid", uid);
                    }
                    
                    FirebaseOptions options = new FirebaseOptions.Builder()
                        .setCredentials(credentials)
                        .setDatabaseUrl(app.configuration()
                                        .getString("ix.admin.firebase.url",
                                                   null))
                        .setDatabaseAuthVariableOverride(auth)
                        .build();
                    
                    fapp = FirebaseApp.initializeApp
                        (options, FirebaseStore.class.getName());
                }
                else {
                    fapp = FirebaseApp.getInstance
                        (FirebaseStore.class.getName());
                }

                db = FirestoreClient.getFirestore(fapp);
                Logger.debug("## firebase loaded: db="+db);
                for (CollectionReference ref : db.getCollections()) {
                    Logger.debug("... collection /"+ref.getId());
                    /*
                    Store store = new Store (pcs, ref);
                    store.documents();
                    store.shutdown();
                    */
                }
            }
            catch (Exception ex) {
                Logger.error("Unable to initialize Firebase!", ex);
            }
        }
        else {
            Logger.warn("No firebase credentials specified!");
        }
    }

    public void onStop () {
        if (db != null) {
            try {
                //db.close();
            }
            catch (Exception ex) {
                Logger.error("Unable to close firebase", ex);
            }
        }
        Logger.info("Plugin "+getClass().getName()+" stopped!");
    }

    public Store getStore (String name) {
        if (db == null)
            throw new RuntimeException ("No firebase instance available!");
        try {
            return new Store (pcs, db, name);
        }
        catch (Exception ex) {
            throw new RuntimeException (ex);
        }
    }

    public void addPropertyChangeListener (PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }
    
    public void removePropertyChangeListener (PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }
}
