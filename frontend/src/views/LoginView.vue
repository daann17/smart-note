<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue';
import { UserOutlined, LockOutlined, MailOutlined } from '@ant-design/icons-vue';
import { message } from 'ant-design-vue';
import { useRoute, useRouter } from 'vue-router';
import axios from 'axios';
import { storeSession } from '../utils/session';

type AuthMode = 'login' | 'register';

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const sendCodeLoading = ref(false);
const resendCountdown = ref(0);
const resetModalVisible = ref(false);
const resetLoading = ref(false);
const resetSendCodeLoading = ref(false);
const resetResendCountdown = ref(0);
const activeTab = ref<AuthMode>('login');

const formState = reactive({
  username: '',
  password: '',
  email: '',
  verificationCode: '',
});

const resetFormState = reactive({
  email: '',
  verificationCode: '',
  newPassword: '',
});

let resendTimer: ReturnType<typeof setInterval> | null = null;
let resetResendTimer: ReturnType<typeof setInterval> | null = null;

const authApi = axios.create({
  baseURL: '/api',
});

const isLogin = computed(() => activeTab.value === 'login');
const canSendCode = computed(() => (
  !isLogin.value
  && !sendCodeLoading.value
  && resendCountdown.value <= 0
  && /^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$/.test(formState.email.trim())
));
const canSendResetCode = computed(() => (
  !resetSendCodeLoading.value
  && resetResendCountdown.value <= 0
  && /^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$/.test(resetFormState.email.trim())
));
const redirectTarget = computed(() => {
  const redirect = route.query.redirect;
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/home';
});

const handleFinish = async (values: typeof formState) => {
  loading.value = true;

  try {
    if (isLogin.value) {
      const response = await authApi.post('/auth/login', {
        username: values.username,
        password: values.password,
      });

      const { token, username, displayName, role } = response.data;
      storeSession({
        token,
        username,
        displayName,
        role,
      });

      message.success('登录成功');
      router.push(redirectTarget.value);
      return;
    }

    await authApi.post('/auth/register', {
      username: values.username,
      password: values.password,
      email: values.email,
      verificationCode: values.verificationCode,
    });

    message.success('注册成功，请登录');
    activeTab.value = 'login';
    formState.password = '';
    formState.verificationCode = '';
  } catch (error: any) {
    console.error('Auth error:', error);
    message.error(error.response?.data?.message || error.response?.data || '操作失败');
  } finally {
    loading.value = false;
  }
};

const handleFinishFailed = (errors: unknown) => {
  console.log('Failed:', errors);
};

const startResendCountdown = (seconds: number) => {
  resendCountdown.value = seconds;
  if (resendTimer) {
    clearInterval(resendTimer);
  }
  resendTimer = setInterval(() => {
    if (resendCountdown.value <= 1) {
      resendCountdown.value = 0;
      if (resendTimer) {
        clearInterval(resendTimer);
        resendTimer = null;
      }
      return;
    }
    resendCountdown.value -= 1;
  }, 1000);
};

const startResetResendCountdown = (seconds: number) => {
  resetResendCountdown.value = seconds;
  if (resetResendTimer) {
    clearInterval(resetResendTimer);
  }
  resetResendTimer = setInterval(() => {
    if (resetResendCountdown.value <= 1) {
      resetResendCountdown.value = 0;
      if (resetResendTimer) {
        clearInterval(resetResendTimer);
        resetResendTimer = null;
      }
      return;
    }
    resetResendCountdown.value -= 1;
  }, 1000);
};

const handleSendVerificationCode = async () => {
  const email = formState.email.trim();
  if (!/^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$/.test(email)) {
    message.warning('请先输入有效邮箱地址');
    return;
  }

  sendCodeLoading.value = true;
  try {
    const response = await authApi.post('/auth/register/code', { email });
    message.success(response.data?.message || '验证码已发送');
    startResendCountdown(60);
  } catch (error: any) {
    message.error(error.response?.data?.message || error.response?.data || '验证码发送失败');
  } finally {
    sendCodeLoading.value = false;
  }
};

const openResetModal = () => {
  resetModalVisible.value = true;
  resetFormState.email = formState.email.trim();
  resetFormState.verificationCode = '';
  resetFormState.newPassword = '';
};

const handleSendResetCode = async () => {
  const email = resetFormState.email.trim();
  if (!/^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$/.test(email)) {
    message.warning('请先输入有效邮箱地址');
    return;
  }

  resetSendCodeLoading.value = true;
  try {
    const response = await authApi.post('/auth/password-reset/code', { email });
    message.success(response.data?.message || '验证码已发送');
    startResetResendCountdown(60);
  } catch (error: any) {
    message.error(error.response?.data?.message || error.response?.data || '验证码发送失败');
  } finally {
    resetSendCodeLoading.value = false;
  }
};

const handleResetPassword = async () => {
  resetLoading.value = true;
  try {
    const response = await authApi.post('/auth/password-reset', {
      email: resetFormState.email,
      verificationCode: resetFormState.verificationCode,
      newPassword: resetFormState.newPassword,
    });
    message.success(response.data?.message || '密码已重置');
    resetModalVisible.value = false;
    activeTab.value = 'login';
    formState.password = '';
  } catch (error: any) {
    message.error(error.response?.data?.message || error.response?.data || '密码重置失败');
  } finally {
    resetLoading.value = false;
  }
};

const toggleMode = () => {
  activeTab.value = isLogin.value ? 'register' : 'login';
  formState.username = '';
  formState.password = '';
  formState.email = '';
  formState.verificationCode = '';
  resendCountdown.value = 0;
  if (resendTimer) {
    clearInterval(resendTimer);
    resendTimer = null;
  }
};

onUnmounted(() => {
  if (resendTimer) {
    clearInterval(resendTimer);
  }
  if (resetResendTimer) {
    clearInterval(resetResendTimer);
  }
});
</script>

<template>
  <div class="auth-page">
    <div class="auth-shell">
      <section class="auth-hero">
        <div class="hero-copy">
          <span class="hero-badge">SmartNote Workspace</span>
          <h1>把零散想法整理成温和、持续生长的知识库</h1>
          <p>
            记录笔记、沉淀结构化知识、管理分享与协作评论，并把 AI 能力嵌入你的创作流程。
          </p>
        </div>

        <div class="hero-grid">
          <article class="hero-card">
            <span class="hero-card-label">写作方式</span>
            <strong>Markdown + 实时预览</strong>
            <small>专注内容本身，同时保留结构、公式、流程图和历史版本。</small>
          </article>
          <article class="hero-card">
            <span class="hero-card-label">知识组织</span>
            <strong>笔记本、标签、知识图谱</strong>
            <small>从碎片记录过渡到长期管理，找到主题之间真正的连接。</small>
          </article>
          <article class="hero-card">
            <span class="hero-card-label">协作体验</span>
            <strong>分享、评论、AI 助手</strong>
            <small>支持公开分享、段落评论、协同编辑和上下文问答。</small>
          </article>
        </div>
      </section>

      <section class="auth-panel">
        <div class="panel-head">
          <img src="/home-logo.png" alt="SmartNote" class="logo" />
          <div>
            <h2>欢迎回来</h2>
            <p>{{ isLogin ? '继续你的整理与创作' : '创建账号，开始构建你的知识空间' }}</p>
          </div>
        </div>

        <a-tabs v-model:activeKey="activeTab" centered class="auth-tabs">
          <a-tab-pane key="login" tab="登录" />
          <a-tab-pane key="register" tab="注册" />
        </a-tabs>

        <a-form
          :model="formState"
          name="auth_form"
          class="auth-form"
          layout="vertical"
          @finish="handleFinish"
          @finishFailed="handleFinishFailed"
        >
          <a-form-item
            label="用户名"
            name="username"
            :rules="[{ required: true, message: '请输入用户名' }]"
          >
            <a-input v-model:value="formState.username" placeholder="请输入用户名" size="large">
              <template #prefix><UserOutlined /></template>
            </a-input>
          </a-form-item>

          <a-form-item
            v-if="!isLogin"
            label="邮箱"
            name="email"
            :rules="[{ required: true, type: 'email', message: '请输入有效邮箱地址' }]"
          >
            <a-input v-model:value="formState.email" placeholder="请输入邮箱" size="large">
              <template #prefix><MailOutlined /></template>
            </a-input>
          </a-form-item>

          <a-form-item
            label="密码"
            name="password"
            :rules="[{ required: true, message: '请输入密码' }]"
          >
            <a-input-password v-model:value="formState.password" placeholder="请输入密码" size="large">
              <template #prefix><LockOutlined /></template>
            </a-input-password>
          </a-form-item>

          <a-form-item
            v-if="!isLogin"
            label="验证码"
            name="verificationCode"
            :rules="[{ required: true, message: '请输入邮箱验证码' }]"
          >
            <div class="verify-code-row">
              <a-input v-model:value="formState.verificationCode" placeholder="请输入 6 位验证码" size="large" />
              <a-button
                html-type="button"
                size="large"
                :loading="sendCodeLoading"
                :disabled="!canSendCode"
                @click="handleSendVerificationCode"
              >
                {{ resendCountdown > 0 ? `${resendCountdown}s 后重发` : '发送验证码' }}
              </a-button>
            </div>
          </a-form-item>

          <a-form-item class="submit-row">
            <a-button type="primary" html-type="submit" block size="large" :loading="loading">
              {{ isLogin ? '进入工作台' : '创建账号' }}
            </a-button>
          </a-form-item>
        </a-form>

        <div v-if="isLogin" class="password-actions">
          <a @click.prevent="openResetModal">忘记密码？</a>
        </div>

        <div class="auth-footer">
          <span>{{ isLogin ? '还没有账号？' : '已经有账号？' }}</span>
          <a @click.prevent="toggleMode">{{ isLogin ? '立即注册' : '直接登录' }}</a>
        </div>
      </section>
    </div>

    <a-modal
      v-model:open="resetModalVisible"
      title="通过邮箱重置密码"
      ok-text="重置密码"
      cancel-text="取消"
      :confirm-loading="resetLoading"
      @ok="handleResetPassword"
    >
      <a-form :model="resetFormState" layout="vertical">
        <a-form-item label="邮箱">
          <a-input v-model:value="resetFormState.email" placeholder="请输入邮箱" size="large">
            <template #prefix><MailOutlined /></template>
          </a-input>
        </a-form-item>

        <a-form-item label="验证码">
          <div class="verify-code-row">
            <a-input v-model:value="resetFormState.verificationCode" placeholder="请输入 6 位验证码" size="large" />
            <a-button
              html-type="button"
              size="large"
              :loading="resetSendCodeLoading"
              :disabled="!canSendResetCode"
              @click="handleSendResetCode"
            >
              {{ resetResendCountdown > 0 ? `${resetResendCountdown}s 后重发` : '发送验证码' }}
            </a-button>
          </div>
        </a-form-item>

        <a-form-item label="新密码">
          <a-input-password v-model:value="resetFormState.newPassword" placeholder="请输入新密码" size="large">
            <template #prefix><LockOutlined /></template>
          </a-input-password>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  padding: 32px 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-shell {
  width: min(1200px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(380px, 440px);
  gap: 28px;
  align-items: stretch;
}

.auth-hero,
.auth-panel {
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(0, 0, 0, 0.1);
  box-shadow: var(--sn-shadow-card);
}

.auth-hero {
  padding: 40px;
  border-radius: 28px;
  background:
    radial-gradient(circle at top right, rgba(0, 117, 222, 0.08), transparent 24%),
    linear-gradient(180deg, #ffffff 0%, #fbfaf8 100%);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.hero-copy {
  max-width: 640px;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: 9999px;
  background: #f2f9ff;
  color: #097fe8;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.auth-hero h1 {
  margin: 18px 0 16px;
  max-width: 760px;
  font-size: clamp(40px, 5vw, 64px);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -2px;
  color: rgba(0, 0, 0, 0.95);
}

.auth-hero p {
  margin: 0;
  max-width: 620px;
  color: #615d59;
  font-size: 18px;
  line-height: 1.7;
}

.hero-grid {
  margin-top: 40px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.hero-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.hero-card-label {
  color: #a39e98;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.hero-card strong {
  display: block;
  margin-top: 12px;
  color: rgba(0, 0, 0, 0.95);
  font-size: 22px;
  line-height: 1.3;
  letter-spacing: -0.4px;
}

.hero-card small {
  display: block;
  margin-top: 10px;
  color: #615d59;
  font-size: 14px;
  line-height: 1.7;
}

.auth-panel {
  padding: 32px;
  border-radius: 24px;
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.logo {
  width: 76px;
  height: 76px;
  object-fit: contain;
  border-radius: 18px;
  background: #f6f5f4;
  padding: 8px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.panel-head h2 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.8px;
  color: rgba(0, 0, 0, 0.95);
}

.panel-head p {
  margin: 8px 0 0;
  color: #615d59;
  font-size: 15px;
}

.auth-tabs {
  margin-bottom: 12px;
}

.auth-form {
  margin-top: 8px;
}

.submit-row {
  margin-top: 6px;
  margin-bottom: 0;
}

.password-actions {
  margin-top: 14px;
  text-align: right;
  font-size: 14px;
}

.auth-footer {
  margin-top: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #615d59;
  font-size: 14px;
}

.auth-footer a,
.password-actions a {
  font-weight: 600;
}

.verify-code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
}

@media (max-width: 1080px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .hero-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .auth-page {
    padding: 16px;
  }

  .auth-hero,
  .auth-panel {
    padding: 24px 20px;
  }

  .panel-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .verify-code-row {
    grid-template-columns: 1fr;
  }
}
</style>
