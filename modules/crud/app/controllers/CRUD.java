package controllers;

import java.util.*;
import java.lang.reflect.*;
import java.lang.annotation.*;
import javax.persistence.*;

import play.*;
import play.mvc.*;
import play.db.Model;
import play.db.jpa.FileAttachment;
import play.db.jpa.JPA;
import play.data.validation.*;
import play.exceptions.*;
import play.i18n.*;

public abstract class CRUD extends Controller {

    @Before
    public static void addType() throws Exception {
        ObjectType type = ObjectType.get(getControllerClass());
        renderArgs.put("type", type);
    }

    public static void index() {
        try {
            render();
        } catch (TemplateNotFoundException e) {
            render("CRUD/index.html");
        }
    }

    public static void list(int page, String search, String searchFields, String orderBy, String order) {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        if (page < 1) {
            page = 1;
        }
        List<Model> objects = type.findPage(page, search, searchFields, orderBy, order, (String) request.args.get("where"));
        Long count = type.count(search, searchFields, (String) request.args.get("where"));
        Long totalCount = type.count(null, null, (String) request.args.get("where"));
        try {
            render(type, objects, count, totalCount, page, orderBy, order);
        } catch (TemplateNotFoundException e) {
            render("CRUD/list.html", type, objects, count, totalCount, page, orderBy, order);
        }
    }

    public static void show(String id) {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        Model object = type.findById(id);
        try {
            render(type, object);
        } catch (TemplateNotFoundException e) {
            render("CRUD/show.html", type, object);
        }
    }

    public static void attachment(String id, String field) throws Exception {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        Model object = type.findById(id);
        FileAttachment attachment = (FileAttachment) object.getClass().getField(field).get(object);
        if (attachment == null) {
            notFound();
        }
        renderBinary(attachment.get(), attachment.filename);
    }

    public static void save(String id) throws Exception {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        Model object = type.findById(id);
        play.db.jpa.JPASupport.edit(object, "object", params.all(), null);
        validation.valid(object);
        if (validation.hasErrors()) {
            renderArgs.put("error", Messages.get("crud.hasErrors"));
            try {
                render(request.controller.replace(".", "/") + "/show.html", type, object);
            } catch (TemplateNotFoundException e) {
                render("CRUD/show.html", type, object);
            }
        }
        object._save();
        flash.success(Messages.get("crud.saved", type.modelName, object._getKey()));
        if (params.get("_save") != null) {
            redirect(request.controller + ".list");
        }
        redirect(request.controller + ".show", object._getKey());
    }

    public static void blank() {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        try {
            render(type);
        } catch (TemplateNotFoundException e) {
            render("CRUD/blank.html", type);
        }
    }

    public static void create() throws Exception {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        Constructor constructor = type.entityClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Model object = (Model) constructor.newInstance();
        play.db.jpa.JPASupport.edit(object, "object", params.all(), null);
        validation.valid(object);
        if (validation.hasErrors()) {
            renderArgs.put("error", Messages.get("crud.hasErrors"));
            try {
                render(request.controller.replace(".", "/") + "/blank.html", type);
            } catch (TemplateNotFoundException e) {
                render("CRUD/blank.html", type);
            }
        }
        object._save();
        flash.success(Messages.get("crud.created", type.modelName, object._getKey()));
        if (params.get("_save") != null) {
            redirect(request.controller + ".list");
        }
        if (params.get("_saveAndAddAnother") != null) {
            redirect(request.controller + ".blank");
        }
        redirect(request.controller + ".show", object._getKey());
    }

    public static void delete(String id) {
        ObjectType type = ObjectType.get(getControllerClass());
        notFoundIfNull(type);
        Model object = type.findById(id);
        try {
            object._delete();
        } catch (Exception e) {
            flash.error(Messages.get("crud.delete.error", type.modelName, object._getKey()));
            redirect(request.controller + ".show", object._getKey());
        }
        flash.success(Messages.get("crud.deleted", type.modelName, object._getKey()));
        redirect(request.controller + ".list");
    }

    // ~~~~~~~~~~~~~
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface For {

        Class value();
    }

    // ~~~~~~~~~~~~~
    static int getPageSize() {
        return Integer.parseInt(Play.configuration.getProperty("crud.pageSize", "30"));
    }

    public static class ObjectType implements Comparable<ObjectType> {

        public Class<? extends CRUD> controllerClass;
        public Class<? extends Model> entityClass;
        public String name;
        public String modelName;
        public String controllerName;

        public ObjectType(Class modelClass) {
            this.modelName = modelClass.getSimpleName();
            this.entityClass = modelClass;
        }

        public ObjectType(String modelClass) throws ClassNotFoundException {
            this(Play.classloader.loadClass(modelClass));
        }

        public static ObjectType forClass(String modelClass) throws ClassNotFoundException {
            return new ObjectType(modelClass);
        }

        public static ObjectType get(Class controllerClass) {
            Class entityClass = getEntityClassForController(controllerClass);
            if (entityClass == null || !Model.class.isAssignableFrom(entityClass)) {
                return null;
            }
            ObjectType type = new ObjectType(entityClass);
            type.name = controllerClass.getSimpleName().replace("$", "");
            type.controllerName = controllerClass.getSimpleName().toLowerCase().replace("$", "");
            type.controllerClass = controllerClass;
            return type;
        }

        public static Class getEntityClassForController(Class controllerClass) {
            if (controllerClass.isAnnotationPresent(For.class)) {
                return ((For) (controllerClass.getAnnotation(For.class))).value();
            }
            for(Type it : controllerClass.getGenericInterfaces()) {
                if(it instanceof ParameterizedType) {
                    ParameterizedType type = (ParameterizedType)it;
                    if(((Class)type.getRawType()).getSimpleName().equals("CRUDWrapper")) {
                        return (Class)type.getActualTypeArguments()[0];
                    }
                }                
            }
            String name = controllerClass.getSimpleName().replace("$", "");
            name = "models." + name.substring(0, name.length() - 1);
            try {
                return Play.classloader.loadClass(name);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        public Object getListAction() {
            return Router.reverse(controllerClass.getName().replace("$", "") + ".list");
        }

        public Object getBlankAction() {
            return Router.reverse(controllerClass.getName().replace("$", "") + ".blank");
        }

        public Long count(String search, String searchFields, String where) {
            String q = "select count(e) from " + entityClass.getName() + " e";
            if (search != null && !search.equals("")) {
                String searchQuery = getSearchQuery(searchFields);
                if (!searchQuery.equals("")) {
                    q += " where (" + searchQuery + ")";
                }
                q += (where != null ? " and " + where : "");
            } else {
                q += (where != null ? " where " + where : "");
            }
            Query query = JPA.em().createQuery(q);
            if (search != null && !search.equals("") && q.indexOf("?1") != -1) {
                query.setParameter(1, "%" + search.toLowerCase() + "%");
            }
            return Long.decode(query.getSingleResult().toString());
        }

        public List findPage(int page, String search, String searchFields, String orderBy, String order, String where) {
            int pageLength = getPageSize();
            String q = "from " + entityClass.getName();
            if (search != null && !search.equals("")) {
                String searchQuery = getSearchQuery(searchFields);
                if (!searchQuery.equals("")) {
                    q += " where (" + searchQuery + ")";
                }
                q += (where != null ? " and " + where : "");
            } else {
                q += (where != null ? " where " + where : "");
            }
            if (orderBy == null && order == null) {
                orderBy = "id";
                order = "ASC";
            }
            if (orderBy == null && order != null) {
                orderBy = "id";
            }
            if (order == null || (!order.equals("ASC") && !order.equals("DESC"))) {
                order = "ASC";
            }
            q += " order by " + orderBy + " " + order;
            Query query = JPA.em().createQuery(q);
            if (search != null && !search.equals("") && q.indexOf("?1") != -1) {
                query.setParameter(1, "%" + search.toLowerCase() + "%");
            }
            query.setFirstResult((page - 1) * pageLength);
            query.setMaxResults(pageLength);
            return query.getResultList();
        }

        public String getSearchQuery(String searchFields) {
            List<String> fields = null;
            if (searchFields != null && !searchFields.equals("")) {
                fields = Arrays.asList(searchFields.split("[ ]"));
            }
            String q = "";
            for (ObjectField field : getFields()) {
                if (field.searchable && (fields == null ? true : fields.contains(field.name))) {
                    if (!q.equals("")) {
                        q += " or ";
                    }
                    q += "lower(" + field.name + ") like ?1";
                }
            }
            return q;
        }

        public Model findById(Object id) {
            Query query = JPA.em().createQuery("from " + entityClass.getName() + " where id = ?");
            try {
                // TODO: I think we need to type of direct bind -> primitive and object binder
                query.setParameter(1, play.data.binding.Binder.directBind(id + "", play.db.jpa.JPASupport.findKeyType(entityClass)));
            } catch (Exception e) {
                throw new RuntimeException("Something bad with id type ?", e);
            }
            return (Model) query.getSingleResult();
        }

        public List<ObjectField> getFields() {
            List fields = new ArrayList();
            for (Field f : entityClass.getDeclaredFields()) {
                if (Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                ObjectField of = new ObjectField(f);
                if (of.type != null) {
                    fields.add(of);
                }
            }
            return fields;
        }

        public ObjectField getField(String name) {
            for (ObjectField field : getFields()) {
                if (field.name.equals(name)) {
                    return field;
                }
            }
            return null;
        }

        public int compareTo(ObjectType other) {
            return modelName.compareTo(other.modelName);
        }

        @Override
        public String toString() {
            return modelName;
        }

        public static class ObjectField {

            public String type = "unknown";
            public String name;
            public String relation;
            public boolean multiple;
            public boolean searchable;
            public Object[] choices;
            public boolean required;
            
            public ObjectField(Field field) {
                if (CharSequence.class.isAssignableFrom(field.getType())) {
                    type = "text";
                    searchable = true;
                    if (field.isAnnotationPresent(MaxSize.class)) {
                        int maxSize = field.getAnnotation(MaxSize.class).value();
                        if (maxSize > 100) {
                            type = "longtext";
                        }
                    }
                    if (field.isAnnotationPresent(Password.class)) {
                        type = "password";
                    }
                }
                if (Number.class.isAssignableFrom(field.getType()) || field.getType().equals(double.class) || field.getType().equals(int.class) || field.getType().equals(long.class)) {
                    type = "number";
                }
                if (Boolean.class.isAssignableFrom(field.getType()) || field.getType().equals(boolean.class)) {
                    type = "boolean";
                }
                if (Date.class.isAssignableFrom(field.getType())) {
                    type = "date";
                }
                if (FileAttachment.class.isAssignableFrom(field.getType())) {
                    type = "file";
                }
                if (Model.class.isAssignableFrom(field.getType())) {
                    if (field.isAnnotationPresent(OneToOne.class)) {
                        if (field.getAnnotation(OneToOne.class).mappedBy().equals("")) {
                            type = "relation";
                            relation = field.getType().getName();
                        }
                    }
                    if (field.isAnnotationPresent(ManyToOne.class)) {
                        type = "relation";
                        relation = field.getType().getName();
                    }
                }
                if (Collection.class.isAssignableFrom(field.getType())) {
                    Class fieldType = (Class) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                    if (field.isAnnotationPresent(OneToMany.class)) {
                        if (field.getAnnotation(OneToMany.class).mappedBy().equals("")) {
                            type = "relation";
                            relation = fieldType.getName();
                            multiple = true;
                        }
                    }
                    if (field.isAnnotationPresent(ManyToMany.class)) {
                        if (field.getAnnotation(ManyToMany.class).mappedBy().equals("")) {
                            type = "relation";
                            relation = fieldType.getName();
                            multiple = true;
                        }
                    }
                }
                if (field.getType().isEnum()) {
                    type = "enum";
                    relation = field.getType().getSimpleName();
                    choices = field.getType().getEnumConstants();
                }
                if (field.isAnnotationPresent(Id.class)) {
                    type = null;
                }
                if (field.isAnnotationPresent(Transient.class)) {
                    type = null;
                }
                if (field.isAnnotationPresent(Required.class)) {
                    required = true;
                }
                name = field.getName();
            }

            public Object[] getChoices() {
                return choices;
            }
        }
    }
}

