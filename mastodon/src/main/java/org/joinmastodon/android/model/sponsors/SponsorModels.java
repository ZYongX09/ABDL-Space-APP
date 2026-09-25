package org.joinmastodon.android.model.sponsors;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.BaseModel;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Server-owned products and presentation. No local product catalogue or entitlement defaults. */
public final class SponsorModels{
	private SponsorModels(){}

	public static class Plan{
		public String id, name, description, currency, durationUnit, purchaseUrl, afdianPlanId, afdianSkuId;
		public int version, priceMinor, durationCount, sortOrder;
		public boolean enabled;
	}
	public static class Benefit{
		public String id, title, description, status, action;
		public int sortOrder;
	}
	public static class Color{
		public String key, name, light, dark;
		public boolean permanentOnly;
	}
	public static class Config{
		public Boolean enabled;
		public int version, freeDailyLimit, sponsorDailyLimit, noticeVersion, minimumReadSeconds;
		public String centerTitle, timezone, noticeTitle, noticeBody, exhaustedTitle, exhaustedBody, sponsorExhaustedBody;
		public String purchaseTitle, defaultColorKey;
		public List<String> purchaseSteps;
		public List<Color> colors;
		public List<Benefit> benefits;
	}
	public static class Sponsor{
		public boolean active, permanent;
		public Long expiresAt;
		public String planName, colorKey, colorLight, colorDark;
		public boolean isActive(){ return active && (permanent || expiresAt!=null && expiresAt>System.currentTimeMillis()/1000); }
	}
	public static class Quota{
		public int limit, used, remaining;
		public long resetsAt;
		public String dayKey;
		public boolean valid(){
			if(limit<0 || used<0 || remaining!=Math.max(0, limit-used) || resetsAt<=0 || dayKey==null) return false;
			try{ return LocalDate.parse(dayKey).toString().equals(dayKey); }
			catch(RuntimeException invalid){ return false; }
		}
	}
	public static class Catalog extends BaseModel{
		public Config config;
		public List<Plan> plans;
		@Override public void postprocess() throws ObjectValidationException{
			if(config==null || config.enabled==null || config.version<1 || config.noticeVersion<1 || config.minimumReadSeconds<5
					|| config.freeDailyLimit<0 || config.sponsorDailyLimit<0 || !"Asia/Shanghai".equals(config.timezone)
					|| config.centerTitle==null || config.purchaseTitle==null || config.purchaseSteps==null || config.purchaseSteps.isEmpty()
					|| config.colors==null || config.benefits==null || plans==null)
				throw new ObjectValidationException("赞助者配置无效，请重试");
			for(String text:List.of(nonNull(config.noticeTitle), nonNull(config.noticeBody), nonNull(config.exhaustedTitle),
					nonNull(config.exhaustedBody), nonNull(config.sponsorExhaustedBody))){
				if(!validTemplate(text)) throw new ObjectValidationException("赞助者提示内容无效");
			}
			if(config.minimumReadSeconds>300 || config.sponsorDailyLimit<config.freeDailyLimit || config.centerTitle.isBlank()
					|| config.purchaseTitle.isBlank() || config.purchaseSteps.size()>20 || config.benefits.size()>30 || config.colors.size()>20 || plans.size()>200)
				throw new ObjectValidationException("赞助者配置超出支持范围");
			for(String step:config.purchaseSteps) if(step==null || step.isBlank() || step.length()>1000) throw new ObjectValidationException("购买步骤无效");
			Set<String> ids=new HashSet<>();
			for(Plan plan:plans){
				if(plan==null || plan.id==null || plan.id.isBlank() || !ids.add(plan.id) || plan.name==null || plan.name.isBlank()
						|| plan.version<1 || plan.priceMinor<0 || !"CNY".equals(plan.currency)
						|| !Set.of("day", "month", "permanent").contains(nonNull(plan.durationUnit))
						|| ("permanent".equals(plan.durationUnit) ? plan.durationCount!=0 : plan.durationCount<=0))
					throw new ObjectValidationException("赞助者方案无效");
			}
			ids.clear();
			for(Color color:config.colors) if(color==null || color.key==null || color.key.isBlank() || !ids.add(color.key)
					|| color.name==null || color.light==null || color.dark==null || !color.light.matches("#[0-9a-fA-F]{6}") || !color.dark.matches("#[0-9a-fA-F]{6}"))
				throw new ObjectValidationException("昵称颜色配置无效");
			ids.clear();
			for(Benefit benefit:config.benefits) if(benefit==null || benefit.id==null || !ids.add(benefit.id) || benefit.title==null
					|| !Set.of("automatic", "available", "coming_soon").contains(nonNull(benefit.status))
					|| !Set.of("none", "color", "original", "claim").contains(nonNull(benefit.action))
					|| ("coming_soon".equals(benefit.status) && !"none".equals(benefit.action)))
				throw new ObjectValidationException("权益配置无效");
		}
		public Plan findPlan(String id){ return id==null || plans==null ? null : plans.stream().filter(p->p!=null && p.enabled && id.equals(p.id)).findFirst().orElse(null); }
	}
	public static class Me extends BaseModel{
		public Sponsor sponsor;
		public Quota quota;
		public boolean noticeRequired;
		public int configVersion;
		public List<String> claimedBenefitIds;
		public String message;
		@Override public void postprocess() throws ObjectValidationException{
			if(sponsor==null || quota==null || !quota.valid() || configVersion<1 || claimedBenefitIds==null)
				throw new ObjectValidationException("赞助者状态无效，请重试");
		}
	}
	public static class Authorization extends BaseModel{
		public String operationId;
		public Quota quota;
		public boolean replayed;
		@Override public void postprocess() throws ObjectValidationException{
			if(operationId==null || quota==null || !quota.valid()) throw new ObjectValidationException("原图授权响应无效");
		}
	}
	public static class Redemption{
		public String id, planName;
		public long redeemedAt;
		public Long expiresAt;
		public boolean permanent;
	}
	public static class History extends BaseModel{
		public List<Redemption> items;
		public int total;
		@Override public void postprocess() throws ObjectValidationException{
			if(items==null || total<0 || items.size()>100) throw new ObjectValidationException("兑换记录无效");
			for(Redemption item:items) if(item==null || item.id==null || item.planName==null || item.redeemedAt<=0
					|| (!item.permanent && (item.expiresAt==null || item.expiresAt<=0)))
				throw new ObjectValidationException("兑换记录不完整，请重试");
		}
	}
	public static boolean validTemplate(String text){
		if(text==null || text.isBlank()) return false;
		// A literal parser avoids differences between Android ICU and desktop JVM regex engines.
		for(int i=0;i<text.length();i++){
			char c=text.charAt(i);
			if(c=='}') return false;
			if(c!='{') continue;
			int end=text.indexOf('}', i+1);
			if(end<0) return false;
			String key=text.substring(i+1, end);
			if(!Set.of("x", "y", "a", "reset").contains(key)) return false;
			i=end;
		}
		return true;
	}
	private static String nonNull(String value){ return value==null ? "" : value; }
}
