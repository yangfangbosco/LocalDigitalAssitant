package com.digitalhuman;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class AdminServer extends NanoHTTPD {

    private static final String TAG = "AdminServer";
    private final Context context;
    private final FaqDatabase faqDb;

    public AdminServer(Context context, int port) {
        super(port);
        this.context = context;
        this.faqDb = FaqDatabase.getInstance(context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            // Admin page
            if (uri.equals("/") || uri.equals("/admin")) {
                return serveAdminPage();
            }

            // API routes
            if (uri.equals("/api/faq")) {
                if (method == Method.GET) return handleGetFaqs();
                if (method == Method.POST) return handleAddFaq(session);
            }

            if (uri.startsWith("/api/faq/")) {
                long id = Long.parseLong(uri.substring("/api/faq/".length()));
                if (method == Method.PUT) return handleUpdateFaq(session, id);
                if (method == Method.DELETE) return handleDeleteFaq(id);
            }

            if (uri.equals("/api/faq/import") && method == Method.POST) {
                return handleImportFaqs(session);
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        } catch (Exception e) {
            Log.e(TAG, "Server error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    // ===== API Handlers =====

    private Response handleGetFaqs() throws Exception {
        List<FaqDatabase.FaqItem> faqs = faqDb.getAllFaqs();
        JSONArray arr = new JSONArray();
        for (FaqDatabase.FaqItem faq : faqs) {
            JSONObject obj = new JSONObject();
            obj.put("id", faq.id);
            obj.put("question", faq.question);
            obj.put("answer", faq.answer);
            arr.put(obj);
        }
        return jsonResponse(Response.Status.OK, arr.toString());
    }

    private Response handleAddFaq(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String question = body.getString("question");
        String answer = body.getString("answer");
        long id = faqDb.addFaq(question, answer);
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("question", question);
        result.put("answer", answer);
        return jsonResponse(Response.Status.CREATED, result.toString());
    }

    private Response handleUpdateFaq(IHTTPSession session, long id) throws Exception {
        JSONObject body = parseBody(session);
        String question = body.getString("question");
        String answer = body.getString("answer");
        faqDb.updateFaq(id, question, answer);
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("question", question);
        result.put("answer", answer);
        return jsonResponse(Response.Status.OK, result.toString());
    }

    private Response handleDeleteFaq(long id) throws Exception {
        faqDb.deleteFaq(id);
        return jsonResponse(Response.Status.OK, "{\"deleted\":" + id + "}");
    }

    private Response handleImportFaqs(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        JSONArray items = body.getJSONArray("items");
        int count = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            faqDb.addFaq(item.getString("question"), item.getString("answer"));
            count++;
        }
        return jsonResponse(Response.Status.OK, "{\"imported\":" + count + "}");
    }

    // ===== Helpers =====

    private JSONObject parseBody(IHTTPSession session) throws Exception {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String json = body.get("postData");
        return new JSONObject(json);
    }

    private Response jsonResponse(Response.Status status, String json) {
        Response resp = newFixedLengthResponse(status, "application/json", json);
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return resp;
    }

    private Response serveAdminPage() {
        try {
            InputStream is = context.getAssets().open("admin.html");
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
            return newFixedLengthResponse(Response.Status.OK, "text/html", new String(bytes));
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Admin page not found");
        }
    }

    public String getDeviceIp() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wi.getIpAddress();
            return String.format("%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
