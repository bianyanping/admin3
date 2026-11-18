<template>
  <div>
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="clearfix">
              <span>基础信息</span>
            </div>
          </template>
          <div class="info">
            <div class="info-image" @click="showDialog">
              <el-avatar :size="100" :src="avatarImg"/>
              <span class="info-edit">
								<i class="el-icon-lx-camerafill"></i>
							</span>
            </div>
            <div class="info-name">{{ username }}</div>
            <div class="info-desc">{{ form.desc || defaultIntro }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="clearfix">
              <span>账户编辑</span>
            </div>
          </template>
          <el-form label-width="90px">
            <el-form-item label="用户名："> {{ username }}</el-form-item>
            <el-form-item label="旧密码：">
              <el-input type="password" v-model="form.old"></el-input>
            </el-form-item>
            <el-form-item label="新密码：">
              <el-input type="password" v-model="form.new"></el-input>
            </el-form-item>
            <el-form-item label="个人简介：">
              <el-input v-model="form.desc"></el-input>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="onSubmit">保存</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
    <el-dialog title="裁剪图片" v-model="dialogVisible" width="600px">
      <vue-cropper
          ref="cropper"
          :src="imgSrc"
          :ready="cropImage"
          :zoom="cropImage"
          :cropmove="cropImage"
          style="width: 100%; height: 400px"
      ></vue-cropper>

      <template #footer>
				<span class="dialog-footer">
					<el-button class="crop-demo-btn" type="primary"
          >选择图片
						<input class="crop-input" type="file" name="image" accept="image/*" @change="setImage"/>
					</el-button>
					<el-button type="primary" @click="saveAvatar">上传并保存</el-button>
				</span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="user">
import {reactive, ref} from 'vue';
import VueCropper from 'vue-cropperjs';
import 'cropperjs/dist/cropper.css';
import {ElMessage} from "element-plus";
import {changePassword, updateIntro} from "../api/user";
import {useBasicStore} from "../store/basic";

// 未设置简介时的兜底文案（语池以服务端 InspirationalQuotes 为准，新用户由后端随机分配）
const defaultIntro = '保持热爱，奔赴山海。';
const form = reactive({
  old: '',
  new: '',
  desc: ''
});
const basicStore = useBasicStore();
const userinfo = basicStore.userinfo;
form.desc = userinfo.intro || '';

const onSubmit = async () => {
  if (form.old || form.new) {
    if (!form.old || !form.new) {
      ElMessage.warning('修改密码需同时输入旧密码和新密码');
      return;
    }
  }
  try {
    if (form.desc !== (userinfo.intro || '')) {
      await updateIntro(form.desc);
      userinfo.intro = form.desc;
      ElMessage.success('个人简介已保存');
    }
    if (form.old && form.new) {
      await changePassword(form.old, form.new);
      ElMessage.success('密码修改成功，请重新登录');
      form.old = '';
      form.new = '';
      // 清空会话并跳转到登录页
      localStorage.clear();
      location.hash = '#/login';
    }
  } catch (e) {
    // 请求异常已由拦截器提示
  }
};
const username = ref(userinfo.username);
const avatarImg = ref(userinfo.avatar || 'avatar.jpg');
const imgSrc = ref('');
const cropImg = ref('');
const dialogVisible = ref(false);
const cropper: any = ref();

const showDialog = () => {
  dialogVisible.value = true;
  imgSrc.value = avatarImg.value;
};

const setImage = (e: any) => {
  const file = e.target.files[0];
  if (!file.type.includes('image/')) {
    return;
  }
  const reader = new FileReader();
  reader.onload = (event: any) => {
    dialogVisible.value = true;
    imgSrc.value = event.target.result;
    cropper.value && cropper.value.replace(event.target.result);
  };
  reader.readAsDataURL(file);
};

const cropImage = () => {
  cropImg.value = cropper.value.getCroppedCanvas().toDataURL();
};

const saveAvatar = () => {
  avatarImg.value = cropImg.value;
  dialogVisible.value = false;
};
</script>

<style scoped>
.info {
  text-align: center;
  padding: 35px 0;
}

.info-image {
  position: relative;
  margin: auto;
  width: 100px;
  height: 100px;
  background: #f8f8f8;
  border: 1px solid #eee;
  border-radius: 50px;
  overflow: hidden;
}

.info-edit {
  display: flex;
  justify-content: center;
  align-items: center;
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.info-edit i {
  color: #eee;
  font-size: 25px;
}

.info-image:hover .info-edit {
  opacity: 1;
}

.info-name {
  margin: 15px 0 10px;
  font-size: 24px;
  font-weight: 500;
  color: #262626;
}

.crop-demo-btn {
  position: relative;
}

.crop-input {
  position: absolute;
  width: 100px;
  height: 40px;
  left: 0;
  top: 0;
  opacity: 0;
  cursor: pointer;
}
</style>
