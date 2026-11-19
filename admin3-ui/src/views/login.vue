<template>
  <div class="login-wrap">
    <div class="ms-login">
      <div class="ms-title">账单流水分析系统</div>
      <el-form :model="param" :rules="rules" ref="login" label-width="0px" class="ms-content">
        <el-form-item prop="username">
          <el-input v-model="param.username" placeholder="username">
            <template #prepend>
              <el-button :icon="User" disabled></el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input
              type="password"
              placeholder="password"
              v-model="param.password"
              @keyup.enter="submitForm(login)"
          >
            <template #prepend>
              <el-button :icon="Lock" disabled></el-button>
            </template>
          </el-input>
        </el-form-item>
        <div class="login-btn">
          <el-button type="primary" @click="submitForm(login)">登录</el-button>
        </div>
        <div class="login-extra">
          <span class="login-register" @click="registerVisible = true">注册账号</span>
        </div>
        <p class="login-tips">Tips : admin/123456</p>
      </el-form>
    </div>

    <!-- 注册账号 -->
    <el-dialog title="注册账号" v-model="registerVisible" width="min(400px, 92vw)">
      <el-form :model="registerForm" :rules="registerRules" ref="registerFormRef" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="registerForm.username" placeholder="请输入用户名"></el-input>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input type="password" v-model="registerForm.password" placeholder="请输入密码"></el-input>
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input type="password" v-model="registerForm.confirmPassword" placeholder="请再次输入密码"></el-input>
        </el-form-item>
        <div class="register-tips">注册成功后账号有效期为 1 天，到期后请联系管理员开通权限。</div>
      </el-form>
      <template #footer>
				<span class="dialog-footer">
					<el-button @click="registerVisible = false">取 消</el-button>
					<el-button type="primary" :loading="registering" @click="submitRegister">注 册</el-button>
				</span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {reactive, ref} from 'vue';
import {useTagsStore} from '../store/tags';
import {useRouter} from 'vue-router';
import type {FormInstance, FormRules} from 'element-plus';
import {ElMessage} from 'element-plus';
import {Lock, User} from '@element-plus/icons-vue';
import {login as reqLogin, register as reqRegister} from "../api/login";
import {useBasicStore} from "../store/basic";

interface LoginInfo {
  username: string;
  password: string;
}

const router = useRouter();
const param = reactive<LoginInfo>({
  username: '',
  password: ''
});

const rules: FormRules = {
  username: [
    {
      required: true,
      message: '请输入用户名',
      trigger: 'blur'
    }
  ],
  password: [{required: true, message: '请输入密码', trigger: 'blur'}]
};
let basicStore = useBasicStore();
const login = ref<FormInstance>();
const submitForm = (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  formEl.validate((valid: boolean) => {
    if (valid) {
      reqLogin(param).then(res => {
        ElMessage.success('登录成功');
        localStorage.setItem('token', res.data.token);
        router.push('/');
        basicStore.setUserinfo(res.data);
      });

    } else {
      ElMessage.error('登录失败');
      return false;
    }
  });
};

const tags = useTagsStore();
tags.clearTags();

// 注册账号
const registerVisible = ref(false);
const registering = ref(false);
const registerFormRef = ref<FormInstance>();
const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: ''
});

const validateConfirmPassword = (rule: any, value: string, callback: (error?: Error) => void) => {
  if (value !== registerForm.password) {
    callback(new Error('两次输入的密码不一致'));
  } else {
    callback();
  }
};

const registerRules: FormRules = {
  username: [{required: true, message: '请输入用户名', trigger: 'blur'}],
  password: [{required: true, message: '请输入密码', trigger: 'blur'}],
  confirmPassword: [
    {required: true, message: '请再次输入密码', trigger: 'blur'},
    {validator: validateConfirmPassword, trigger: 'blur'}
  ]
};

const submitRegister = async () => {
  if (!registerFormRef.value) return;
  try {
    await registerFormRef.value.validate();
  } catch (e) {
    return;
  }
  registering.value = true;
  try {
    const res = await reqRegister({username: registerForm.username, password: registerForm.password});
    ElMessage.success(`注册成功，账号有效期 ${res.data.validDays} 天`);
    registerVisible.value = false;
    // 注册成功后回填用户名，方便直接登录
    param.username = registerForm.username;
    param.password = '';
    registerForm.username = '';
    registerForm.password = '';
    registerForm.confirmPassword = '';
  } catch (e) {
    // 错误提示已由请求拦截器统一处理
  } finally {
    registering.value = false;
  }
};
</script>

<style scoped>
.login-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  background-image: url(../assets/img/login-bg.jpg);
  background-size: cover;
  background-position: center;
}

.ms-title {
  width: 100%;
  line-height: 50px;
  text-align: center;
  font-size: 20px;
  color: #fff;
  border-bottom: 1px solid #ddd;
}

.ms-login {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 350px;
  transform: translate(-50%, -50%);
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.3);
  overflow: hidden;
}

.ms-content {
  padding: 30px 30px;
}

.login-btn {
  text-align: center;
}

.login-btn button {
  width: 100%;
  height: 36px;
  margin-bottom: 10px;
}

.login-tips {
  font-size: 12px;
  line-height: 30px;
  color: #fff;
}

.login-extra {
  text-align: center;
  margin-bottom: 4px;
}

.login-register {
  font-size: 13px;
  color: #fff;
  cursor: pointer;
  text-decoration: underline;
}

.register-tips {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding-left: 80px;
}

@media (max-width: 768px) {
  .ms-login {
    width: 88vw;
  }

  .ms-title {
    font-size: 18px;
  }

  .ms-content {
    padding: 20px 18px;
  }
}
</style>
