package com.digitalhuman;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class FaqDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "faq.db";
    private static final int DB_VERSION = 1;

    private static FaqDatabase instance;

    public static synchronized FaqDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new FaqDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private FaqDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE faq (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "question TEXT NOT NULL," +
                "answer TEXT NOT NULL," +
                "keywords TEXT DEFAULT ''," +
                "created_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS faq");
        onCreate(db);
    }

    // ===== CRUD =====

    public long addFaq(String question, String answer) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("question", question);
        values.put("answer", answer);
        values.put("keywords", extractKeywords(question));
        return db.insert("faq", null, values);
    }

    public boolean updateFaq(long id, String question, String answer) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("question", question);
        values.put("answer", answer);
        values.put("keywords", extractKeywords(question));
        return db.update("faq", values, "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    public boolean deleteFaq(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete("faq", "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    public List<FaqItem> getAllFaqs() {
        List<FaqItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, question, answer FROM faq ORDER BY id DESC", null);
        while (c.moveToNext()) {
            list.add(new FaqItem(c.getLong(0), c.getString(1), c.getString(2)));
        }
        c.close();
        return list;
    }

    // ===== RAG Search =====

    public List<FaqItem> search(String query, int limit) {
        List<FaqItem> results = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        // Keyword-based search using LIKE matching on question + keywords
        String[] words = query.toLowerCase().split("\\s+");
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();

        for (String word : words) {
            if (word.length() < 2) continue;
            if (where.length() > 0) where.append(" OR ");
            where.append("(LOWER(question) LIKE ? OR LOWER(keywords) LIKE ? OR LOWER(answer) LIKE ?)");
            String pattern = "%" + word + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        if (where.length() == 0) return results;

        String sql = "SELECT id, question, answer FROM faq WHERE " + where + " LIMIT " + limit;
        Cursor c = db.rawQuery(sql, args.toArray(new String[0]));
        while (c.moveToNext()) {
            results.add(new FaqItem(c.getLong(0), c.getString(1), c.getString(2)));
        }
        c.close();
        return results;
    }

    // ===== Helpers =====

    private String extractKeywords(String text) {
        // Simple keyword extraction: lowercase, remove common words
        String lower = text.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        String[] stopWords = {"the", "a", "an", "is", "are", "was", "were", "what", "where",
                "when", "how", "who", "which", "do", "does", "did", "can", "could", "will",
                "would", "should", "may", "might", "to", "of", "in", "on", "at", "for",
                "with", "and", "or", "not", "no", "yes", "i", "you", "we", "they", "it",
                "my", "your", "our", "their", "its", "this", "that", "be", "have", "has"};
        StringBuilder keywords = new StringBuilder();
        for (String word : lower.split("\\s+")) {
            boolean isStop = false;
            for (String sw : stopWords) {
                if (sw.equals(word)) { isStop = true; break; }
            }
            if (!isStop && word.length() > 1) {
                if (keywords.length() > 0) keywords.append(" ");
                keywords.append(word);
            }
        }
        return keywords.toString();
    }

    public static class FaqItem {
        public final long id;
        public final String question;
        public final String answer;

        public FaqItem(long id, String question, String answer) {
            this.id = id;
            this.question = question;
            this.answer = answer;
        }
    }
}
