package cn.componentvault.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 库存和流水在同一事务中写入，禁止负库存。 */
public final class InventoryStore extends SQLiteOpenHelper {
    public static final long MAX_QUANTITY = 1_000_000_000L;
    private static final String[] FIELDS = {"code", "platform", "model", "brand", "footprint", "category", "resistance", "capacitance", "inductance", "color", "voltage", "power", "current", "description", "location", "note", "raw", "photo"};

    public InventoryStore(Context context) { super(context, "inventory.db", null, 6); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE parts (id INTEGER PRIMARY KEY, identity TEXT NOT NULL, code TEXT NOT NULL, platform TEXT NOT NULL DEFAULT '', model TEXT NOT NULL, brand TEXT NOT NULL, footprint TEXT NOT NULL, category TEXT NOT NULL, description TEXT NOT NULL, location TEXT NOT NULL, note TEXT NOT NULL, raw TEXT NOT NULL, photo TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>=0 AND quantity<=1000000000), UNIQUE(identity, location))");
        db.execSQL("CREATE TABLE movements (id INTEGER PRIMARY KEY, part_id INTEGER NOT NULL REFERENCES parts(id), delta INTEGER NOT NULL, balance INTEGER NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX movements_part ON movements(part_id, id)");
        createCategories(db);
        addPassiveColumns(db);
        addElectricalColumns(db);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE parts ADD COLUMN platform TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 3) {
            createCategories(db);
            db.execSQL("INSERT OR IGNORE INTO categories(name) SELECT DISTINCT trim(category) FROM parts WHERE trim(category)<>''");
        }
        if(oldVersion<4) {
            addPassiveColumns(db);
            // 旧库存仅从已保存的原文与规格提取明确单位，不更改数量或流水。
            try(Cursor c=db.query("parts",new String[]{"id","raw","description","category"},null,null,null,null,null)) {
                while(c.moveToNext()) {
                    String text=c.getString(1)+"\n"+c.getString(2), kind=c.getString(3);
                    ContentValues v=new ContentValues();
                    for(String field:new String[]{"resistance","capacitance","inductance"}) v.put(field,PassiveValues.first(text,field,kind));
                    db.update("parts",v,"id=?",new String[]{Long.toString(c.getLong(0))});
                }
            }
        }
        if(oldVersion<5) refineLabelCategories(db);
        if(oldVersion<6) {
            addElectricalColumns(db);
            try(Cursor c=db.query("parts",new String[]{"id","raw","description"},null,null,null,null,null)) {
                while(c.moveToNext()) {
                    String text=c.getString(1)+"\n"+c.getString(2);
                    ContentValues values=new ContentValues(); values.put("color",ElectricalValues.color(text));
                    values.put("voltage",ElectricalValues.first(text,"voltage"));
                    values.put("power",ElectricalValues.first(text,"power"));
                    values.put("current",ElectricalValues.first(text,"current"));
                    db.update("parts",values,"id=?",new String[]{Long.toString(c.getLong(0))});
                }
            }
        }
    }

    private void addPassiveColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE parts ADD COLUMN resistance TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE parts ADD COLUMN capacitance TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE parts ADD COLUMN inductance TEXT NOT NULL DEFAULT ''");
    }

    private void addElectricalColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE parts ADD COLUMN color TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE parts ADD COLUMN voltage TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE parts ADD COLUMN power TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE parts ADD COLUMN current TEXT NOT NULL DEFAULT ''");
    }

    private void refineLabelCategories(SQLiteDatabase db) {
        // 保留用户维护的类别，只增加本次新增的标签细分类。
        java.util.List<String> additions=java.util.Arrays.asList(LabelCategories.LED,LabelCategories.FAST_DIODE,"贴片电阻","厚膜电阻","薄膜电阻","贴片电容","铝电解电容","钽电容","功率电感","肖特基二极管","稳压二极管","开关二极管","整流二极管");
        for(String name:additions) db.insertWithOnConflict("categories",null,content("name",name),SQLiteDatabase.CONFLICT_IGNORE);
        java.util.List<String> names=new java.util.ArrayList<>(additions);
        try(Cursor c=db.query("categories",new String[]{"name"},null,null,null,null,null)) { while(c.moveToNext()) names.add(c.getString(0)); }
        try(Cursor c=db.query("parts",new String[]{"id","category","raw"},null,null,null,null,null)) {
            while(c.moveToNext()) {
                String old=c.getString(1), resolved=LabelCategories.complete(old,names);
                if(java.util.Arrays.asList("电阻","电容","电感","二极管","LED").contains(old)) {
                    String label=LabelCategories.fromLabel(c.getString(2),names);
                    if(!label.isEmpty()) resolved=label;
                }
                if(!resolved.equals(old) && !LabelCategories.truncated(resolved)) {
                    db.insertWithOnConflict("categories",null,content("name",resolved),SQLiteDatabase.CONFLICT_IGNORE);
                    db.update("parts",content("category",resolved),"id=?",new String[]{Long.toString(c.getLong(0))});
                }
            }
        }
    }

    private void createCategories(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (name TEXT PRIMARY KEY COLLATE NOCASE NOT NULL)");
        for (String name:LabelCategories.standard()) {
            ContentValues v=new ContentValues(); v.put("name",name); db.insertWithOnConflict("categories",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public List<String> categories() {
        List<String> names=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("categories",new String[]{"name"},null,null,null,null,"name COLLATE LOCALIZED")) {
            while(c.moveToNext()) names.add(c.getString(0));
        }
        return names;
    }

    public void addCategory(String name) {
        String clean=cleanCategory(name);
        ContentValues v=new ContentValues(); v.put("name",clean);
        if(getWritableDatabase().insertWithOnConflict("categories",null,v,SQLiteDatabase.CONFLICT_IGNORE)==-1)
            throw new IllegalArgumentException("该分类已存在");
    }

    public void ensureCategory(String name) {
        String clean=cleanCategory(name);
        ContentValues v=new ContentValues(); v.put("name",clean);
        getWritableDatabase().insertWithOnConflict("categories",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void renameCategory(String oldName,String newName) {
        String clean=cleanCategory(newName);
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if(db.update("categories",content("name",clean),"name=?",new String[]{oldName})==0)
                throw new IllegalArgumentException("分类不存在");
            db.update("parts",content("category",clean),"category=?",new String[]{oldName});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void deleteCategory(String name,String replacement) {
        if(name.equalsIgnoreCase(replacement)) throw new IllegalArgumentException("请选择其他分类作为替代");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if(!existsCategory(db,replacement)) throw new IllegalArgumentException("替代分类不存在");
            db.update("parts",content("category",replacement),"category=?",new String[]{name});
            if(db.delete("categories","name=?",new String[]{name})==0) throw new IllegalArgumentException("分类不存在");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void setCategory(long partId,String category) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if(!existsCategory(db,category)) throw new IllegalArgumentException("分类不存在，请重新选择");
            if(db.update("parts",content("category",category),"id=?",new String[]{Long.toString(partId)})==0)
                throw new IllegalArgumentException("物料不存在");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void setPassiveValues(long partId,String resistance,String capacitance,String inductance) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Part p=get(partId);
            if(p==null) throw new IllegalArgumentException("物料不存在");
            p.resistance=resistance; p.capacitance=capacitance; p.inductance=inductance;
            if(p.code.isEmpty() && p.model.isEmpty() && resistance.isEmpty() && capacitance.isEmpty() && inductance.isEmpty()) throw new IllegalArgumentException("请保留主要参数，或先填写型号 / 编码");
            Part same=duplicate(p);
            if(same!=null && same.id!=partId) throw new IllegalArgumentException("相同位置已有这些参数的物料，请先核对库存，避免重复建档");
            ContentValues v=new ContentValues();
            v.put("resistance",resistance); v.put("capacitance",capacitance); v.put("inductance",inductance);
            v.put("identity",identity(p));
            db.update("parts",v,"id=?",new String[]{Long.toString(partId)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void setElectricalValues(long partId,String color,String voltage,String power,String current) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Part p=get(partId); if(p==null) throw new IllegalArgumentException("物料不存在");
            p.color=color; p.voltage=voltage; p.power=power; p.current=current;
            Part same=duplicate(p);
            if(same!=null && same.id!=partId) throw new IllegalArgumentException("相同位置已有这些额定参数的物料，请先核对库存");
            ContentValues v=new ContentValues(); v.put("color",color); v.put("voltage",voltage); v.put("power",power); v.put("current",current);
            v.put("identity",identity(p)); db.update("parts",v,"id=?",new String[]{Long.toString(partId)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private boolean existsCategory(SQLiteDatabase db,String name) {
        try(Cursor c=db.query("categories",new String[]{"name"},"name=?",new String[]{name},null,null,null)) { return c.moveToFirst(); }
    }

    public void applyCatalog(long id,Part product,String sourceUrl) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Part current=get(id);
            if(current==null || !current.code.equalsIgnoreCase(product.code)) throw new IllegalArgumentException("物料已删除或编号已改变，请重新查询");
            ContentValues v=new ContentValues();
            for(var field:LcscProductParser.fields(product).entrySet()) v.put(field.getKey(),field.getValue());
            v.put("description",product.description);
            v.put("raw",current.raw+"\n[立创商品资料]\n"+sourceUrl+"\n"+product.description);
            if(!product.category.isEmpty()) db.insertWithOnConflict("categories",null,content("name",product.category),SQLiteDatabase.CONFLICT_IGNORE);
            db.update("parts",v,"id=?",new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private String cleanCategory(String name) {
        String clean=name==null?"":name.trim();
        if(clean.isEmpty() || clean.length()>40) throw new IllegalArgumentException("分类名称需为 1 至 40 个字符");
        if(clean.equalsIgnoreCase("全部")) throw new IllegalArgumentException("“全部”是筛选选项，不能作为分类名称");
        return clean;
    }

    private ContentValues content(String key,String value) { ContentValues v=new ContentValues(); v.put(key,value); return v; }

    private String identity(Part p) {
        // JSON 数组避免字段中分隔符导致的标识碰撞。
        JSONArray key = new JSONArray();
        if (!p.code.trim().isEmpty()) key.put("code").put(p.code.trim().toUpperCase(java.util.Locale.ROOT));
        else key.put("model").put(p.model.trim().toUpperCase(java.util.Locale.ROOT))
                .put(p.footprint.trim().toUpperCase(java.util.Locale.ROOT)).put(p.brand.trim().toUpperCase(java.util.Locale.ROOT))
                .put(p.resistance).put(p.capacitance).put(p.inductance).put(p.color).put(p.voltage).put(p.power).put(p.current);
        return key.toString();
    }

    public Part duplicate(Part p) {
        String where;
        String[] args;
        if(!p.code.trim().isEmpty()) { where="code=? COLLATE NOCASE AND location=?"; args=new String[]{p.code,p.location}; }
        else {
            where="code='' AND model=? COLLATE NOCASE AND footprint=? COLLATE NOCASE AND brand=? COLLATE NOCASE AND resistance=? AND capacitance=? AND inductance=? AND color=? COLLATE NOCASE AND voltage=? AND power=? AND current=? AND location=?";
            args=new String[]{p.model,p.footprint,p.brand,p.resistance,p.capacitance,p.inductance,p.color,p.voltage,p.power,p.current,p.location};
        }
        try (Cursor c = getReadableDatabase().query("parts", null, where, args, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public long receive(Part p) {
        if (p.model.trim().isEmpty() && p.code.trim().isEmpty() && p.resistance.isEmpty() && p.capacitance.isEmpty() && p.inductance.isEmpty()) throw new IllegalArgumentException("请填写型号、编码或阻值 / 容值 / 感值");
        if (p.quantity <= 0 || p.quantity > MAX_QUANTITY) throw new IllegalArgumentException("数量必须为 1 至 10 亿之间的整数");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Part existing = duplicate(p);
            long id;
            if (existing != null) {
                id = existing.id;
                changeInternal(db, id, p.quantity, "补货入库");
                ContentValues missing = new ContentValues();
                if (existing.platform.isEmpty()) missing.put("platform", p.platform);
                if (existing.model.isEmpty()) missing.put("model", p.model);
                if (existing.footprint.isEmpty()) missing.put("footprint", p.footprint);
                if (existing.brand.isEmpty()) missing.put("brand", p.brand);
                if (existing.resistance.isEmpty()) missing.put("resistance", p.resistance);
                if (existing.capacitance.isEmpty()) missing.put("capacitance", p.capacitance);
                if (existing.inductance.isEmpty()) missing.put("inductance", p.inductance);
                if (existing.color.isEmpty()) missing.put("color", p.color);
                if (existing.voltage.isEmpty()) missing.put("voltage", p.voltage);
                if (existing.power.isEmpty()) missing.put("power", p.power);
                if (existing.current.isEmpty()) missing.put("current", p.current);
                if (missing.size() > 0) db.update("parts", missing, "id=?", new String[]{Long.toString(id)});
            } else {
                ContentValues values = values(p);
                values.put("identity", identity(p));
                values.put("quantity", p.quantity);
                id = db.insertOrThrow("parts", null, values);
                movement(db, id, p.quantity, p.quantity, "首次入库");
            }
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    public void change(long id, long delta, String reason) {
        if (delta == 0) throw new IllegalArgumentException("变动数量不能为零");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try { changeInternal(db, id, delta, reason); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }

    /** 删除物料及流水并返回私有目录中的照片路径，数据库删除以单一事务提交。 */
    public String deletePart(long id) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            String photo;
            try(Cursor c=db.query("parts",new String[]{"photo"},"id=?",new String[]{Long.toString(id)},null,null,null)) {
                if(!c.moveToFirst()) throw new IllegalArgumentException("物料已不存在");
                photo=c.getString(0);
            }
            db.delete("movements","part_id=?",new String[]{Long.toString(id)});
            if(db.delete("parts","id=?",new String[]{Long.toString(id)})!=1)
                throw new IllegalStateException("物料删除失败");
            db.setTransactionSuccessful();
            return photo;
        } finally { db.endTransaction(); }
    }

    private void changeInternal(SQLiteDatabase db, long id, long delta, String reason) {
        Part p = get(id);
        if (p == null) throw new IllegalArgumentException("物料不存在");
        if (delta < -p.quantity || delta > MAX_QUANTITY - p.quantity) throw new IllegalArgumentException("库存不足或超出数量上限");
        ContentValues values = new ContentValues();
        values.put("quantity", p.quantity + delta);
        db.update("parts", values, "id=?", new String[]{Long.toString(id)});
        movement(db, id, delta, p.quantity + delta, reason);
    }

    private void movement(SQLiteDatabase db, long id, long delta, long balance, String reason) {
        ContentValues v = new ContentValues();
        v.put("part_id", id); v.put("delta", delta); v.put("balance", balance);
        v.put("reason", reason); v.put("created_at", System.currentTimeMillis());
        db.insertOrThrow("movements", null, v);
    }

    public Part get(long id) {
        try (Cursor c = getReadableDatabase().query("parts", null, "id=?", new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public List<Part> search(String query, String category) {
        List<Part> result = new ArrayList<>();
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String q = "%" + escaped + "%";
        List<String> args=new ArrayList<>(), expressions=new ArrayList<>();
        for(String field:new String[]{"category","model","code","footprint","brand","location","description","note","platform","color"}) {
            expressions.add(field+" LIKE ? ESCAPE '\\'"); args.add(q);
        }
        for(String field:new String[]{"resistance","capacitance","inductance","voltage","power","current"}) {
            expressions.add("REPLACE("+field+",' ','') LIKE ? ESCAPE '\\'"); args.add(parameterQuery(query,field));
        }
        String where="("+String.join(" OR ",expressions)+")";
        if (!category.equals("全部")) { where += " AND category=?"; args.add(category); }
        try (Cursor c = getReadableDatabase().query("parts", null, where, args.toArray(new String[0]), null, null, "id DESC")) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    public long[] stats() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(quantity),0), COALESCE(SUM(CASE WHEN quantity=0 THEN 1 ELSE 0 END),0) FROM parts", null)) {
            c.moveToFirst(); return new long[]{c.getLong(0), c.getLong(1), c.getLong(2)};
        }
    }

    public List<String> history(long id) {
        List<String> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT created_at, delta, balance, reason FROM movements WHERE part_id=? ORDER BY id DESC LIMIT 100", new String[]{Long.toString(id)})) {
            java.text.DateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            while (c.moveToNext()) rows.add(format.format(new java.util.Date(c.getLong(0))) + "  " + (c.getLong(1) > 0 ? "+" : "") + c.getLong(1) + "  → " + c.getLong(2) + "\n" + c.getString(3));
        }
        return rows;
    }

    public String exportJson() throws Exception {
        JSONObject root = new JSONObject(); root.put("format", "component-vault"); root.put("version", 6);
        JSONArray categoryRows=new JSONArray(); for(String category:categories()) categoryRows.put(category);
        root.put("categories",categoryRows);
        JSONArray parts = new JSONArray();
        for (Part p : search("", "全部")) {
            JSONObject row = new JSONObject();
            ContentValues v = values(p);
            for (String field : FIELDS) row.put(field, field.equals("photo") ? "" : v.getAsString(field));
            row.put("id", p.id); row.put("quantity", p.quantity); parts.put(row);
        }
        root.put("parts", parts);
        JSONArray movements = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM movements ORDER BY id", null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (String field : new String[]{"part_id", "delta", "balance", "created_at"}) row.put(field, c.getLong(c.getColumnIndexOrThrow(field)));
                row.put("reason", c.getString(c.getColumnIndexOrThrow("reason"))); movements.put(row);
            }
        }
        root.put("movements", movements);
        return root.toString(2);
    }

    public String exportCsv() {
        StringBuilder b = new StringBuilder("\ufeff类别,阻值,容值,感值,颜色,封装,耐压,功率,电流,物料编码,平台,型号,品牌,规格,存放位置,备注,库存\r\n");
        for (Part p : search("", "全部")) {
            for (String value : new String[]{p.category,p.resistance,p.capacitance,p.inductance,p.color,p.footprint,p.voltage,p.power,p.current,p.code,p.platform,p.model,p.brand,p.description,p.location,p.note}) {
                // 防止表格软件将用户输入解释为公式。
                if (value.matches("^[=+@\\-\\t\\r].*")) value = "'" + value;
                b.append('"').append(value.replace("\"", "\"\"")).append("\",");
            }
            b.append(p.quantity).append("\r\n");
        }
        return b.toString();
    }

    private ContentValues values(Part p) {
        ContentValues v = new ContentValues();
        String[] data = {p.code,p.platform,p.model,p.brand,p.footprint,p.category,p.resistance,p.capacitance,p.inductance,p.color,p.voltage,p.power,p.current,p.description,p.location,p.note,p.raw,p.photo};
        for (int i=0; i<FIELDS.length; i++) v.put(FIELDS[i], data[i]);
        return v;
    }

    private Part read(Cursor c) {
        Part p = new Part(); p.id=c.getLong(c.getColumnIndexOrThrow("id"));
        p.quantity=c.getLong(c.getColumnIndexOrThrow("quantity"));
        p.code=str(c,"code"); p.platform=str(c,"platform"); p.model=str(c,"model"); p.brand=str(c,"brand"); p.footprint=str(c,"footprint");
        p.category=str(c,"category"); p.description=str(c,"description"); p.location=str(c,"location");
        p.resistance=str(c,"resistance"); p.capacitance=str(c,"capacitance"); p.inductance=str(c,"inductance");
        p.color=str(c,"color"); p.voltage=str(c,"voltage"); p.power=str(c,"power"); p.current=str(c,"current");
        p.note=str(c,"note"); p.raw=str(c,"raw"); p.photo=str(c,"photo"); return p;
    }

    private String str(Cursor c, String key) { return c.getString(c.getColumnIndexOrThrow(key)); }

    private String parameterQuery(String query,String field) {
        String canonical=field.equals("voltage") || field.equals("power") || field.equals("current")?ElectricalValues.first(query,field):PassiveValues.first(query,field,"");
        String value=(canonical.isEmpty()?PassiveValues.normalize(query):canonical).replace(" ","");
        return "%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    }
}
