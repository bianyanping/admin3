package tech.wetech.admin3.common;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 个人简介默认励志语池。
 *
 * <p>新增用户时由后端从这里随机取一句作为默认简介；个人中心未设置简介时也回退到同一处文案。
 * 想增删文案只改这一处即可。
 */
public final class InspirationalQuotes {

  private static final List<String> QUOTES = List.of(
    "保持热爱，奔赴山海。",
    "种一棵树最好的时间是十年前，其次是现在。",
    "慢慢来，比较快。",
    "所有的努力，都会绕个圈回来。",
    "心之所向，素履以往。",
    "不积跬步，无以至千里。",
    "把每一件小事做好，就是不平凡。",
    "山高路远，看世界，也找自己。",
    "认真生活，就能找到生活藏起来的糖果。",
    "今天多学一点，明天少求一点。",
    "星光不问赶路人，时光不负有心人。",
    "与其感慨路难行，不如马上出发。"
  );

  private InspirationalQuotes() {
  }

  public static String random() {
    return QUOTES.get(ThreadLocalRandom.current().nextInt(QUOTES.size()));
  }
}
