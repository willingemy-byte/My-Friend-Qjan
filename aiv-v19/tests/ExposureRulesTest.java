package fr.erick.journallocal;

import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class ExposureRulesTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static JSONObject app(String name,Object grant)throws Exception{return new JSONObject().put("package_name","com.example.app").put("version_code",7).put("permissions",new JSONArray().put(new JSONObject().put("name",name).put("granted",grant)));}
    private static JSONObject list(String name)throws Exception{return new JSONObject().put("count",1).put("kind","permissions").put("source","Synthetic verified list").put("same_version",true).put("version_code",7).put("surface","google_play").put("permission_names",new JSONArray().put(name));}
    public static void main(String[] args)throws Exception{
        JSONObject ordinary=app("android.permission.INTERNET",true);
        check(ExposureRules.assess(ordinary,new JSONObject()).getInt("level")==0,"missing lists never invent A1");
        JSONObject evidence=new JSONObject().put("penalty_evidence",new JSONObject().put("visibility",new JSONObject().put("principal",list("android.permission.INTERNET"))));
        check(ExposureRules.assess(ordinary,evidence).getInt("level")==1,"verified complete Play list establishes A1");
        check(ExposureRules.assess(app("android.permission.RECEIVE_BOOT_COMPLETED",false),new JSONObject()).getInt("level")==3,"A3 capability is restored even when currently denied");
        check(DefenseRules.level("android.permission.RECEIVE_BOOT_COMPLETED","","")==0,"A3 does not alter the A4/A5 review queue");
        JSONObject denied=ExposureRules.assess(app("android.permission.WRITE_SETTINGS",false),new JSONObject());
        check(denied.getInt("level")==5&&"NON_ACCORDEE".equals(denied.getJSONArray("findings").getJSONObject(0).getString("effective_status")),"declared A5 is distinct from grant state");
        check(!denied.getJSONArray("findings").getJSONObject(0).getBoolean("operation_observed"),"grade never fabricates actual operation");
        JSONArray cases=new JSONArray(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        for(int i=0;i<cases.length();i++){
            JSONObject fixture=cases.getJSONObject(i),actual=ExposureRules.assess(fixture.getJSONObject("app"),fixture.getJSONObject("reference")),expected=fixture.getJSONObject("expected");
            JSONObject projected=new JSONObject().put("level",actual.getInt("level")).put("visibility_status",actual.getString("visibility_status")).put("disclosure_verified",actual.getBoolean("disclosure_verified")).put("findings",actual.getJSONArray("findings"));
            JSONObject counts=new JSONObject();for(int n=2;n<=5;n++)counts.put(String.valueOf(n),actual.getJSONObject("counts").getInt(String.valueOf(n)));projected.put("counts",counts);
            check(projected.similar(expected),fixture.getString("label")+"\nExpected: "+expected+"\nActual: "+projected);
        }
        System.out.println("ExposureRules: "+checks+" checks passed, including "+cases.length()+" parity cases against the existing JS model");
    }
}
