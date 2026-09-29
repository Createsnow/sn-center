import { createApp } from "vue";
import { createPinia } from "pinia";
import * as Icons from "@element-plus/icons-vue";
import App from "./App.vue";
import router from "./router";
import { i18n, setLocale, detectLocale } from "./i18n";
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/message-box/style/css";
import "element-plus/es/components/notification/style/css";
import "./assets/styles/tokens.css";
import "./style.css";

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.use(i18n);
setLocale(detectLocale(), false);
for (const [name, comp] of Object.entries(Icons)) {
  app.component(name, comp);
}
app.mount("#app");
